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

package com.haramveil.detection.mode3

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.os.Process
import android.os.SystemClock
import com.haramveil.data.local.HaramVeilPreferenceKeys
import com.haramveil.data.local.ProtectionPreferencesRepository
import com.haramveil.data.local.StatsRepository
import com.haramveil.data.models.InferenceAccelerator
import com.haramveil.data.models.ProtectionSettings
import com.haramveil.data.models.ResolvedAccelerator
import com.haramveil.utils.DetectionBus
import com.haramveil.utils.DetectionEvent
import com.haramveil.utils.DetectionTriggerMode
import com.haramveil.utils.DispatcherProvider
import com.haramveil.utils.DebugLog
import com.haramveil.utils.Mode1WakeRequest
import com.haramveil.utils.ScreenCaptureManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max

class Mode3Processor(
    context: Context,
    private val accessibilityServiceProvider: () -> AccessibilityService?,
    private val protectionPreferencesRepository: ProtectionPreferencesRepository,
    private val settingsProvider: () -> ProtectionSettings,
    private val dispatcherProvider: DispatcherProvider = DispatcherProvider(),
) {
    private val applicationContext = context.applicationContext
    private val processorScope = CoroutineScope(SupervisorJob() + dispatcherProvider.default)
    private val screenCaptureManager = ScreenCaptureManager(dispatcherProvider)
    private val statsRepository = StatsRepository.getInstance(context)
    private val modelSelector = ModelSelector(
        context = context,
        protectionPreferencesRepository = protectionPreferencesRepository,
    )
    private val preprocessor = NudeNetPreprocessor(dispatcherProvider)
    private val inferenceEngine = NudeNetInferenceEngine(
        context = context,
        dispatcherProvider = dispatcherProvider,
        acceleratorProvider = ::resolveAccelerator,
    )
    private var processorJob: Job? = null
    private var lastInferenceStartedAtMs = 0L
    private var consecutiveSlowInferences = 0
    private var thermalRateLimitOverrideMs: Long? = null

    /** package -> (content fingerprint, completion time) of the last real inference. */
    private val lastInferenceByPackage = HashMap<String, LastInferenceRecord>()

    /**
     * Set by the thermal guard when hardware acceleration repeatedly underperforms on
     * this device: that session falls back to the CPU immediately instead of waiting for
     * the user to re-run the benchmark.
     */
    @Volatile
    private var acceleratorFallbackOverride: ResolvedAccelerator? = null

    private fun resolveAccelerator(): ResolvedAccelerator {
        acceleratorFallbackOverride?.let { fallback -> return fallback }

        val settings = settingsProvider()
        val benchmark = settings.accelerationBenchmark
        return when (settings.selectedAccelerator) {
            InferenceAccelerator.AUTO ->
                benchmark?.let { summary ->
                    ResolvedAccelerator(summary.winner, summary.nnapiUseFp16)
                } ?: ResolvedAccelerator(InferenceAccelerator.CPU)
            InferenceAccelerator.NNAPI ->
                ResolvedAccelerator(InferenceAccelerator.NNAPI, benchmark?.nnapiUseFp16 ?: false)
            InferenceAccelerator.XNNPACK -> ResolvedAccelerator(InferenceAccelerator.XNNPACK)
            InferenceAccelerator.CPU -> ResolvedAccelerator(InferenceAccelerator.CPU)
        }
    }

    fun start() {
        if (processorJob?.isActive == true) {
            return
        }

        val collectorStartedAtMs = System.currentTimeMillis()
        processorJob = processorScope.launch(dispatcherProvider.default) {
            warmModelSafely()

            // mode1WakeRequests is a conflating StateFlow, so a burst of wake requests collapses
            // to the newest one and a replayed request from before this collector started is
            // dropped by the timestamp check instead of re-running inference on a stale screen.
            DetectionBus.mode1WakeRequests
                .filterNotNull()
                .filter { wakeRequest -> wakeRequest.issuedAtEpochMs >= collectorStartedAtMs }
                .collect { wakeRequest ->
                    try {
                        processTrigger(wakeRequest)
                    } catch (cancellation: CancellationException) {
                        rethrowCancellation(cancellation)
                    } catch (failure: Exception) {
                        DebugLog.w(applicationContext, LogTag) {
                            "Mode 3 processing failed for ${wakeRequest.scanResult.packageName}: ${failure.message}"
                        }
                    }
                }
        }
    }

    private suspend fun warmModelSafely() {
        try {
            inferenceEngine.warm(modelSelector.select())
        } catch (cancellation: CancellationException) {
            rethrowCancellation(cancellation)
        } catch (failure: Exception) {
            DebugLog.w(applicationContext, LogTag) {
                "Mode 3 model warm-up failed: ${failure.message}"
            }
        }
    }

    private fun rethrowCancellation(cancellation: CancellationException): Nothing =
        throw cancellation

    fun stop() {
        processorJob?.cancel()
        processorJob = null
        lastInferenceStartedAtMs = 0L
        consecutiveSlowInferences = 0
        thermalRateLimitOverrideMs = null
        acceleratorFallbackOverride = null
        synchronized(lastInferenceByPackage) { lastInferenceByPackage.clear() }
    }

    fun destroy() {
        stop()
        inferenceEngine.close()
        processorScope.cancel()
    }

    private suspend fun processTrigger(trigger: Mode1WakeRequest) {
        withContext(dispatcherProvider.default) {
            // THREAD_PRIORITY_BACKGROUND would confine this thread to the ~5%-CPU
            // background cgroup, starving inference; LESS_FAVORABLE stays in the
            // foreground group at slightly reduced priority.
            Process.setThreadPriority(Process.THREAD_PRIORITY_LESS_FAVORABLE)

            val settings = settingsProvider()
            if (!settings.mode3Enabled || !trigger.wakeMode3) {
                return@withContext
            }

            val configuredRateLimitMs = if (settings.strictModeEnabled) {
                HaramVeilPreferenceKeys.StrictMode3InferenceIntervalMs
            } else {
                settings.mode3InferenceIntervalMs
            }
            val activeRateLimitMs = thermalRateLimitOverrideMs
                ?: configuredRateLimitMs.coerceIn(minimumValue = 1_000L, maximumValue = 2_000L)
            val now = SystemClock.elapsedRealtime()
            if (now - lastInferenceStartedAtMs < activeRateLimitMs) {
                DetectionBus.publishMode3Clear(
                    packageName = trigger.scanResult.packageName,
                    details = "Visual scan skipped to respect the ${activeRateLimitMs}ms rate limit.",
                )
                return@withContext
            }
            val packageName = trigger.scanResult.packageName

            // Inference on provably unchanged content is wasted energy. The tree fingerprint
            // does not see video pixels, so the skip is bounded: after the rescan interval a
            // same-hash frame is analysed again, keeping moving content covered.
            val contentHash = trigger.nodeHash
            if (contentHash != 0) {
                val lastRun = synchronized(lastInferenceByPackage) { lastInferenceByPackage[packageName] }
                if (lastRun != null &&
                    lastRun.contentHash == contentHash &&
                    now - lastRun.completedAtMs < UnchangedContentRescanIntervalMs
                ) {
                    lastInferenceStartedAtMs = now
                    DetectionBus.publishMode3Clear(
                        packageName = packageName,
                        details = "Visual scan skipped: screen content unchanged since the last inference.",
                    )
                    return@withContext
                }
            }
            lastInferenceStartedAtMs = now

            val accessibilityService = accessibilityServiceProvider() ?: run {
                DetectionBus.publishMode3Clear(
                    packageName = trigger.scanResult.packageName,
                    details = "Accessibility service instance was unavailable for visual scanning.",
                )
                return@withContext
            }

            // Visual inference needs the complete screen: the model was trained on whole
            // scenes, and the banded OCR crop would both hide content outside the bands
            // and present a stacked-strip layout the detector has never seen.
            val captureBitmap = screenCaptureManager.captureFullScreen(accessibilityService) ?: run {
                DetectionBus.publishMode3Clear(
                    packageName = trigger.scanResult.packageName,
                    details = "Screenshot capture is unavailable on this device or Android version.",
                )
                return@withContext
            }

            try {
                val modelConfig = modelSelector.select()
                inferenceEngine.warm(modelConfig)
                val preparedBitmap = screenCaptureManager.prepareForVisualModel(
                    bitmap = captureBitmap,
                    inputSize = modelConfig.inputSize,
                )
                val tensor = preprocessor.createTensor(
                    bitmap = preparedBitmap,
                    modelConfig = modelConfig,
                    environment = inferenceEngine.environment(),
                )
                val onnxOutput = try {
                    // The tensor is owned here: closing it in the finally block guarantees
                    // release even when session creation fails before run() can consume it.
                    tensor.use { inputTensor ->
                        inferenceEngine.run(
                            tensor = inputTensor,
                            modelConfig = modelConfig,
                        )
                    }
                } finally {
                    if (preparedBitmap !== captureBitmap) {
                        recycleBitmap(preparedBitmap)
                    }
                }
                val detectionResult = inferenceEngine.evaluate(onnxOutput)
                updateThermalGuard(
                    packageName = trigger.scanResult.packageName,
                    configuredRateLimitMs = configuredRateLimitMs,
                    inferenceTimeMs = onnxOutput.inferenceTimeMs,
                )
                if (contentHash != 0) {
                    synchronized(lastInferenceByPackage) {
                        lastInferenceByPackage[packageName] = LastInferenceRecord(
                            contentHash = contentHash,
                            completedAtMs = SystemClock.elapsedRealtime(),
                        )
                    }
                }

                val summaryDetails =
                    "Model ${modelConfig.visualModel.displayName} completed in ${onnxOutput.inferenceTimeMs}ms."

                if (detectionResult.isUnsafe) {
                    val matchDetails =
                        "$summaryDetails Unsafe class ${detectionResult.className} scored " +
                            String.format(Locale.ROOT, "%.2f", detectionResult.confidence) + "."
                    statsRepository.logBlock(
                        packageName = trigger.scanResult.packageName,
                        mode = 3,
                        detail = "visual_content",
                        lockdownMs = settings.lockdownDurationMs,
                    )
                    DetectionBus.publishMode3Triggered(
                        packageName = trigger.scanResult.packageName,
                        matchDetails = matchDetails,
                    )
                    DetectionBus.publishVeilRequested(
                        packageName = trigger.scanResult.packageName,
                        triggerMode = DetectionTriggerMode.MODE_3,
                        matchDetails = matchDetails,
                    )
                    DebugLog.w(applicationContext, LogTag) {
                        "Mode 3 unsafe detection for ${trigger.scanResult.packageName}. $matchDetails"
                    }
                } else {
                    DetectionBus.publishMode3Clear(
                        packageName = trigger.scanResult.packageName,
                        details = "$summaryDetails Top class ${detectionResult.className}.",
                    )
                    DebugLog.i(applicationContext, LogTag) {
                        "Mode 3 clear result for ${trigger.scanResult.packageName}. $summaryDetails"
                    }
                }
            } finally {
                recycleBitmap(captureBitmap)
            }
        }
    }

    private fun updateThermalGuard(
        packageName: String,
        configuredRateLimitMs: Long,
        inferenceTimeMs: Long,
    ) {
        if (inferenceTimeMs > SlowInferenceThresholdMs) {
            consecutiveSlowInferences += 1
        } else {
            consecutiveSlowInferences = 0
            thermalRateLimitOverrideMs = null
            return
        }

        if (consecutiveSlowInferences >= SlowInferenceStrikeCount) {
            val newRateLimitMs = max(configuredRateLimitMs, ThermalGuardRateLimitMs)
            // In-process override only: a temporary thermal condition must never be written
            // into the stored preferences, where it would outlive the condition and keep
            // slowing visual scans down forever.
            thermalRateLimitOverrideMs = newRateLimitMs
            consecutiveSlowInferences = 0

            val fallbackMessage = if (acceleratorFallbackOverride == null &&
                resolveAccelerator().accelerator != InferenceAccelerator.CPU
            ) {
                // Repeatedly slow inference while hardware acceleration is active means the
                // benchmark verdict no longer matches reality (throttling, driver trouble).
                // The rest of this session runs on the CPU; a re-run of the benchmark can
                // re-enable acceleration once the device cools down.
                acceleratorFallbackOverride = ResolvedAccelerator(InferenceAccelerator.CPU)
                " Thermal guard also fell back from hardware acceleration to the CPU for this session."
            } else {
                ""
            }

            val warningMessage =
                "Thermal guard raised visual scan pacing to ${newRateLimitMs}ms after repeated slow inferences in $packageName.$fallbackMessage"
            DetectionBus.publishMode3Clear(
                packageName = packageName,
                details = warningMessage,
            )
            DebugLog.w(applicationContext, LogTag) { warningMessage }
        }
    }

    private fun recycleBitmap(bitmap: Bitmap) {
        if (!bitmap.isRecycled) {
            bitmap.recycle()
        }
    }

    private companion object {
        const val LogTag = "HaramVeilMode3"
        const val SlowInferenceThresholdMs = 1_500L
        const val ThermalGuardRateLimitMs = 2_000L
        const val SlowInferenceStrikeCount = 3

        /** Window during which a same-fingerprint screen is skipped as unchanged. */
        const val UnchangedContentRescanIntervalMs = 5_000L
    }

    private data class LastInferenceRecord(
        val contentHash: Int,
        val completedAtMs: Long,
    )
}
