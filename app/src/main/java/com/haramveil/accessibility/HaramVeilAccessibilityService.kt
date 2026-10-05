/*
 * HaramVeil
 * Copyright (C) 2026 HaramVeil Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.haramveil.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
import com.haramveil.data.local.HaramVeilPreferenceKeys
import com.haramveil.data.local.ProtectionPreferencesRepository
import com.haramveil.data.local.StatsRepository
import com.haramveil.data.models.ProtectionSettings
import com.haramveil.detection.mode1.MatchSource
import com.haramveil.detection.mode1.RiskLevel
import com.haramveil.detection.mode1.ScanResult
import com.haramveil.detection.mode2.Mode2Processor
import com.haramveil.detection.mode1.UITreeScanner
import com.haramveil.detection.mode3.Mode3Processor
import com.haramveil.overlay.VeilOverlayController
import com.haramveil.security.AppLockdownManager
import com.haramveil.service.ProtectionBootstrapStore
import com.haramveil.utils.DetectionBus
import com.haramveil.utils.DetectionTriggerMode
import com.haramveil.utils.DispatcherProvider
import com.haramveil.utils.DebugLog
import com.haramveil.utils.Mode1WakeRequest
import com.haramveil.utils.ThrottleManager
import com.haramveil.utils.ThrottledScanTrigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HaramVeilAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dispatcherProvider = DispatcherProvider()
    private val uiTreeScanner = UITreeScanner()
    private lateinit var protectionPreferencesRepository: ProtectionPreferencesRepository
    private lateinit var appLockdownManager: AppLockdownManager
    private lateinit var statsRepository: StatsRepository
    private lateinit var bootstrapStore: ProtectionBootstrapStore
    private val protectionSettingsState = MutableStateFlow(ProtectionSettings())
    private var collectorsStarted = false
    private var collectorJobs: List<Job> = emptyList()
    private lateinit var throttleManager: ThrottleManager
    private lateinit var mode2Processor: Mode2Processor
    private lateinit var mode3Processor: Mode3Processor
    private var processedMode1EventsInWindow = 0
    private var processedMetricsWindowStartedAtMs = SystemClock.elapsedRealtime()
    private var lastScanHeartbeatMs = 0L

    override fun onCreate() {
        super.onCreate()
        // A repeat onCreate means the service was rebound: tear down the previous
        // processors and throttle collector before building new ones, otherwise the
        // orphaned processors keep consuming DetectionBus forever.
        if (::throttleManager.isInitialized) {
            throttleManager.stop()
        }
        destroyProcessors()
        stopCollectors()
        collectorsStarted = false
        protectionPreferencesRepository = ProtectionPreferencesRepository(applicationContext)
        appLockdownManager = AppLockdownManager(applicationContext)
        statsRepository = StatsRepository.getInstance(applicationContext)
        bootstrapStore = ProtectionBootstrapStore(applicationContext)
        throttleManager = ThrottleManager(
            scope = serviceScope,
            dispatcherProvider = dispatcherProvider,
        ) { pendingEvent ->
            buildScanTrigger(pendingEvent.packageName, pendingEvent.eventType)
        }
        mode2Processor = Mode2Processor(
            context = applicationContext,
            accessibilityServiceProvider = { this },
            protectionPreferencesRepository = protectionPreferencesRepository,
            settingsProvider = { protectionSettingsState.value },
            dispatcherProvider = dispatcherProvider,
        )
        mode3Processor = Mode3Processor(
            context = applicationContext,
            accessibilityServiceProvider = { this },
            protectionPreferencesRepository = protectionPreferencesRepository,
            settingsProvider = { protectionSettingsState.value },
            dispatcherProvider = dispatcherProvider,
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            canRetrieveWindowContent = true
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            flags = flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }
        if (!collectorsStarted) {
            startCollectors()
            collectorsStarted = true
        }
        VeilOverlayController.start(applicationContext)
        DebugLog.i(applicationContext, LogTag) { "Accessibility service connected." }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val windowEvent = event ?: return
        if (windowEvent.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            windowEvent.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            return
        }

        // A throw from an event callback tears the whole service down.
        runCatching { handleAccessibilityEvent(windowEvent) }
            .onFailure { error ->
                if (error is CancellationException) {
                    throw error
                }
                DebugLog.w(applicationContext, LogTag) {
                    "Accessibility event handling failed: ${error.javaClass.simpleName}"
                }
            }
    }

    private fun handleAccessibilityEvent(event: AccessibilityEvent) {
        val currentSettings = protectionSettingsState.value
        if (!currentSettings.protectionEnabled) {
            return
        }

        val packageName = event.packageName
            ?.toString()
            ?.takeIf(String::isNotBlank)
            ?: rootInActiveWindow?.packageName?.toString()?.takeIf(String::isNotBlank)
            ?: return

        val isBrowserPackage = uiTreeScanner.isBrowserPackage(packageName)
        val isHighRiskPackage = uiTreeScanner.isHighRiskPackage(packageName)
        val isMonitoredPackage = packageName in currentSettings.monitoredPackages
        val isMode3ForcedPackage = packageName in currentSettings.mode3ForcedPackages

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            DetectionBus.publishWindowStateObserved(
                packageName = packageName,
                eventType = event.eventType,
            )

            if (appLockdownManager.isLocked(packageName)) {
                // Re-extend the lockdown duration instead of just showing the veil
                appLockdownManager.lockApp(packageName, currentSettings.lockdownDurationMs)
                serviceScope.launch(dispatcherProvider.io) {
                    statsRepository.logBlock(
                        packageName = packageName,
                        mode = 1,
                        detail = "lockdown_retrigger",
                        lockdownMs = currentSettings.lockdownDurationMs,
                    )
                }
                DetectionBus.publishVeilRequested(
                    packageName = packageName,
                    triggerMode = DetectionTriggerMode.LOCKDOWN,
                    matchDetails = "App reopen intercepted while its lockdown timer was still active.",
                )
                DebugLog.i(applicationContext, LogTag) {
                    "Lockdown re-triggered for $packageName before content scan."
                }
                return
            }
        }

        val isScannablePackage = isBrowserPackage || isHighRiskPackage ||
            isMonitoredPackage || isMode3ForcedPackage
        if (!isScannablePackage) {
            return
        }

        val anyModeEnabled = currentSettings.mode1Enabled ||
            currentSettings.mode2Enabled ||
            currentSettings.mode3Enabled
        if (!anyModeEnabled) {
            return
        }

        throttleManager.submit(
            packageName = packageName,
            eventType = event.eventType,
        )
    }

    override fun onInterrupt() {
        DebugLog.w(applicationContext, LogTag) { "Accessibility service interrupted by the system." }
    }

    override fun onDestroy() {
        stopCollectors()
        if (::throttleManager.isInitialized) {
            throttleManager.stop()
        }
        destroyProcessors()
        collectorsStarted = false
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun destroyProcessors() {
        if (::mode2Processor.isInitialized) {
            runCatching { mode2Processor.destroy() }
        }
        if (::mode3Processor.isInitialized) {
            runCatching { mode3Processor.destroy() }
        }
    }

    private fun stopCollectors() {
        collectorJobs.forEach { job -> runCatching { job.cancel() } }
        collectorJobs = emptyList()
    }

    private fun startCollectors() {
        val settingsJob = serviceScope.launch(dispatcherProvider.io) {
            protectionPreferencesRepository.settingsFlow.collectLatest { settings ->
                protectionSettingsState.value = settings
                val effectiveFrameSkipMs = if (settings.strictModeEnabled) {
                    HaramVeilPreferenceKeys.StrictFrameSkipIntervalMs
                } else {
                    settings.frameSkipIntervalMs
                }
                throttleManager.updateDebounceMs(effectiveFrameSkipMs)
                if (settings.protectionEnabled && settings.mode2Enabled) {
                    mode2Processor.start()
                } else {
                    mode2Processor.stop()
                }
                if (settings.protectionEnabled && settings.mode3Enabled) {
                    mode3Processor.start()
                } else {
                    mode3Processor.stop()
                }
            }
        }

        val scanTriggerJob = serviceScope.launch(dispatcherProvider.default) {
            throttleManager.scanTriggers.collect { trigger ->
                runCatching { processScanTrigger(trigger) }
                    .onFailure { error ->
                        if (error is CancellationException) {
                            throw error
                        }
                        DebugLog.w(applicationContext, LogTag) {
                            "Mode 1 scan trigger failed for ${trigger.packageName}: ${error.javaClass.simpleName}"
                        }
                    }
            }
        }

        collectorJobs = listOf(settingsJob, scanTriggerJob)
    }

    private suspend fun processScanTrigger(
        trigger: ThrottledScanTrigger,
    ) {
        try {
            recordDebugProcessedEvent()
            recordScanHeartbeat()
            val currentSettings = protectionSettingsState.value
            val isHighRiskApp = trigger.isHighRisk
            val isBrowserApp = trigger.isBrowser
            val isMonitoredApp = isBrowserApp || trigger.packageName in currentSettings.monitoredPackages
            val isMode3Forced = trigger.packageName in currentSettings.mode3ForcedPackages
            val mode3Eligible = isBrowserApp || isHighRiskApp || isMode3Forced ||
                trigger.packageName in currentSettings.monitoredPackages
            val wakeMode2 = currentSettings.mode2Enabled
            val wakeMode3 = currentSettings.mode3Enabled && mode3Eligible

            if (!currentSettings.mode1Enabled) {
                // Mode 1 is off: no text scan, no stats, no veil, but Mode 2/3 still
                // need the wake-up so their own detection pipelines keep running.
                if (wakeMode2 || wakeMode3) {
                    DebugLog.d(applicationContext, LogTag) {
                        "Mode 1 disabled; skipping text scan for ${trigger.packageName}."
                    }
                    DetectionBus.publishMode1Triggered(
                        Mode1WakeRequest(
                            scanResult = ScanResult(
                                triggered = false,
                                matchedKeyword = null,
                                matchSource = null,
                                packageName = trigger.packageName,
                                riskLevel = RiskLevel.LOW,
                            ),
                            wakeMode2 = wakeMode2,
                            wakeMode3 = wakeMode3,
                        ),
                    )
                }
                return
            }

            val scanResult = uiTreeScanner.scan(
                rootNode = trigger.rootNode,
                packageName = trigger.packageName,
                keywordBlocklist = currentSettings.keywordBlocklist,
                domainBlocklist = currentSettings.domainBlocklist,
                isMonitoredApp = isMonitoredApp,
                isBrowser = isBrowserApp,
                isHighRisk = isHighRiskApp,
            )

            // When Mode 1's text/keyword scan did not match, we still need to wake the
            // visual (Mode 3) pipeline for eligible apps so purely graphical adult content
            // is caught. In that case we publish the wake request without showing a
            // Mode 1 veil (there was no textual trigger to report).
            if (!scanResult.triggered) {
                if (wakeMode3) {
                    DebugLog.d(applicationContext, LogTag) {
                        "Mode 1 text scan clean for ${trigger.packageName}; waking Mode 3 visual scan."
                    }
                    DetectionBus.publishMode1Triggered(
                        Mode1WakeRequest(
                            scanResult = scanResult,
                            wakeMode2 = wakeMode2,
                            wakeMode3 = true,
                        ),
                    )
                }
                return
            }

            DebugLog.d(applicationContext, LogTag) { "Wake Request: mode2=${wakeMode2}, mode3=${wakeMode3} for ${trigger.packageName} (isBrowser=${isBrowserApp}, isHighRisk=${isHighRiskApp}, isForced=${isMode3Forced})" }
            val matchDetails = buildMatchDetails(scanResult, isHighRiskApp, isMonitoredApp)

            scanResult.matchedKeyword?.let { matchedKeyword ->
                statsRepository.logBlock(
                    packageName = scanResult.packageName,
                    mode = 1,
                    detail = matchedKeyword,
                    lockdownMs = currentSettings.lockdownDurationMs,
                )
            }

            DetectionBus.publishMode1Triggered(
                Mode1WakeRequest(
                    scanResult = scanResult,
                    wakeMode2 = wakeMode2,
                    wakeMode3 = wakeMode3,
                ),
            )

            when (scanResult.riskLevel) {
                // Always show veil immediately for HIGH risk content
                RiskLevel.HIGH -> publishMode1Veil(scanResult.packageName, matchDetails)

                // Monitored-app presence alone only veils in strict mode
                RiskLevel.MEDIUM -> if (currentSettings.strictModeEnabled) {
                    publishMode1Veil(scanResult.packageName, matchDetails)
                }

                RiskLevel.LOW -> Unit
            }

            DebugLog.i(applicationContext, LogTag) {
                "Mode 1 triggered for ${scanResult.packageName} with ${scanResult.riskLevel} risk. $matchDetails"
            }
        } finally {
            trigger.recycle()
        }
    }

    private fun buildScanTrigger(
        packageName: String,
        eventType: Int,
    ): ThrottledScanTrigger? {
        val activeRoot = rootInActiveWindow ?: return null
        val rootSnapshot = AccessibilityNodeInfo.obtain(activeRoot)
        try {
            val isBrowser = uiTreeScanner.isBrowserPackage(packageName)
            val isHighRisk = uiTreeScanner.isHighRiskPackage(packageName)
            val nodeHash = uiTreeScanner.computeNodeHash(rootSnapshot, isBrowser, isHighRisk)
            return ThrottledScanTrigger(
                isBrowser = isBrowser,
                isHighRisk = isHighRisk,
                packageName = packageName,
                eventType = eventType,
                nodeHash = nodeHash,
                rootNode = rootSnapshot,
            )
        } catch (error: Throwable) {
            runCatching { rootSnapshot.recycle() }
            DebugLog.w(applicationContext, LogTag) {
                "Failed to snapshot window for $packageName: ${error.javaClass.simpleName}"
            }
            return null
        }
    }

    private fun publishMode1Veil(
        packageName: String,
        matchDetails: String,
    ) {
        DetectionBus.publishVeilRequested(
            packageName = packageName,
            triggerMode = DetectionTriggerMode.MODE_1,
            matchDetails = matchDetails,
        )
    }

    private fun buildMatchDetails(
        scanResult: ScanResult,
        isHighRiskApp: Boolean,
        isMonitoredApp: Boolean,
    ): String {
        return scanResult.matchedKeyword?.let { keyword ->
            when (scanResult.matchSource) {
                MatchSource.DOMAIN ->
                    "Blocked domain \"$keyword\" appeared in browser content."
                else ->
                    "Matched keyword \"$keyword\" in visible UI content."
            }
        } ?: if (isHighRiskApp) {
            "Monitored package appears on the high-risk app list."
        } else if (isMonitoredApp) {
            "Monitored package is enabled for scanning."
        } else {
            "Package appears on-screen for scanning."
        }
    }

    private fun recordDebugProcessedEvent() {
        if (!DebugLog.isDebuggable(applicationContext)) {
            return
        }

        processedMode1EventsInWindow += 1
        val elapsedMs = SystemClock.elapsedRealtime() - processedMetricsWindowStartedAtMs
        if (elapsedMs < DebugMetricsWindowMs) {
            return
        }

        DebugLog.d(applicationContext, LogTag) {
            "Mode 1 events processed per minute: $processedMode1EventsInWindow"
        }
        processedMode1EventsInWindow = 0
        processedMetricsWindowStartedAtMs = SystemClock.elapsedRealtime()
    }

    private fun recordScanHeartbeat() {
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastScanHeartbeatMs < ScanHeartbeatDebounceMs) {
            return
        }
        lastScanHeartbeatMs = nowMs
        bootstrapStore.updateScanHeartbeat(nowMs)
    }

    private companion object {
        const val LogTag = "HaramVeilMode1"
        const val DebugMetricsWindowMs = 60_000L
        const val ScanHeartbeatDebounceMs = 5_000L
    }
}
