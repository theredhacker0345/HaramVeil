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

package com.haramveil.service

import android.content.BroadcastReceiver
import android.content.Context
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.UserManager
import androidx.work.WorkManager
import com.haramveil.accessibility.isHaramVeilAccessibilityServiceEnabled
import com.haramveil.data.local.StatsCleanupWorker
import com.haramveil.overlay.VeilOverlayController
import com.haramveil.utils.DebugLog
import com.haramveil.utils.RootAccessHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class HaramVeilForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var bootstrapStore: ProtectionBootstrapStore
    private var heartbeatJob: Job? = null
    private var unlockedRuntimeStarted = false
    private var userUnlockedReceiver: BroadcastReceiver? = null
    private var intentionalStopRequested = false

    @Volatile
    private var isScreenOn = true
    private var screenStateReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        bootstrapStore = ProtectionBootstrapStore(applicationContext)
        ProtectionNotificationHelper.ensureChannels(applicationContext)
        registerUserUnlockedReceiver()
        registerScreenStateReceiver()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ActionStop) {
            intentionalStopRequested = true
            stopRuntime(cancelRecovery = true)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!bootstrapStore.isBootstrapReady()) {
            intentionalStopRequested = true
            stopRuntime(cancelRecovery = true)
            stopSelf()
            return START_NOT_STICKY
        }

        intentionalStopRequested = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ requires explicit foreground service type matching the manifest.
            startForeground(
                ProtectionNotificationHelper.ForegroundNotificationId,
                ProtectionNotificationHelper.buildForegroundNotification(applicationContext),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(
                ProtectionNotificationHelper.ForegroundNotificationId,
                ProtectionNotificationHelper.buildForegroundNotification(applicationContext),
            )
        }
        bootstrapStore.markServiceRunning(true)
        bootstrapStore.updateServiceHeartbeat()
        startHeartbeatLoop()
        ServiceHealthAlarmReceiver.schedule(applicationContext)

        // Catch up on a safe window whose deadline passed while this service was dead.
        // Credential-protected settings are unreadable before unlock, so the alarm and the
        // worker own that window until then.
        if (isUserUnlocked()) {
            catchUpOnSafeWindow()
        }

        if (!bootstrapStore.isProtectionEnabled()) {
            // Keep the health alarm while a safe window is still open so the deadline is
            // enforced even if this process dies in the meantime.
            intentionalStopRequested = true
            stopRuntime(cancelRecovery = bootstrapStore.isSafeWindowActive())
            stopSelf()
            return START_NOT_STICKY
        }

        if (isUserUnlocked()) {
            enterUnlockedRuntimeIfNeeded()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        unregisterUserUnlockedReceiver()
        unregisterScreenStateReceiver()
        stopRuntime(cancelRecovery = intentionalStopRequested)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startHeartbeatLoop() {
        if (heartbeatJob?.isActive == true) {
            return
        }
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                // The heartbeat exists so a killed service can be detected and restarted;
                // while the screen is off nothing is scanning, so the marker only needs to
                // stay fresh on the slow cycle instead of waking the disk every 30s.
                if (isScreenOn) {
                    bootstrapStore.updateServiceHeartbeat()
                    delay(HeartbeatIntervalMs)
                } else {
                    delay(ScreenOffHeartbeatIntervalMs)
                    bootstrapStore.updateServiceHeartbeat()
                }
            }
        }
    }

    private fun catchUpOnSafeWindow() {
        serviceScope.launch {
            try {
                resumeProtectionIfPauseElapsed(applicationContext)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                DebugLog.w(applicationContext, LogTag) {
                    "Safe-window catch-up failed: ${error.javaClass.simpleName}: ${error.message}"
                }
            }
        }
    }

    private fun enterUnlockedRuntimeIfNeeded() {
        if (unlockedRuntimeStarted) {
            return
        }
        unlockedRuntimeStarted = true

        VeilOverlayController.start(applicationContext)
        ProtectionHealthWorker.enqueue(applicationContext)
        StatsCleanupWorker.enqueue(applicationContext)

        val rootModeEnabled = RootAccessHelper.isRootAvailable()
        if (rootModeEnabled) {
            RootAccessHelper.tryLowerNiceValue()
            if (!isHaramVeilAccessibilityServiceEnabled(applicationContext)) {
                RootAccessHelper.tryRestoreAccessibilityService(applicationContext)
            }
        }

        if (!isHaramVeilAccessibilityServiceEnabled(applicationContext)) {
            ProtectionNotificationHelper.showAccessibilityReenablePrompt(applicationContext)
        }
    }

    private fun stopRuntime(
        cancelRecovery: Boolean,
    ) {
        heartbeatJob?.cancel()
        heartbeatJob = null
        bootstrapStore.markServiceRunning(false)
        if (cancelRecovery) {
            ServiceHealthAlarmReceiver.cancel(applicationContext)
            WorkManager.getInstance(applicationContext)
                .cancelUniqueWork(ProtectionHealthWorker.UniqueWorkName)
            WorkManager.getInstance(applicationContext)
                .cancelUniqueWork(StatsCleanupWorker.UniqueWorkName)
        }
        // stopForeground() has to run before the veil service is stopped: Android 12+ only
        // allows one or two background foreground-service starts per ten seconds, and
        // starting the veil runtime right after this service drops out of the foreground
        // can trip that limit.
        stopForegroundCompat()
        VeilOverlayController.stop(applicationContext)
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun isUserUnlocked(): Boolean {
        val userManager = getSystemService(UserManager::class.java) ?: return true
        return userManager.isUserUnlocked
    }

    private fun registerUserUnlockedReceiver() {
        if (userUnlockedReceiver != null) {
            return
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_USER_UNLOCKED &&
                    bootstrapStore.isBootstrapReady() &&
                    bootstrapStore.isProtectionEnabled()
                ) {
                    enterUnlockedRuntimeIfNeeded()
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_USER_UNLOCKED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
        userUnlockedReceiver = receiver
    }

    private fun unregisterUserUnlockedReceiver() {
        val receiver = userUnlockedReceiver ?: return
        runCatching {
            unregisterReceiver(receiver)
        }
        userUnlockedReceiver = null
    }

    companion object {
        const val ActionStart = "com.haramveil.service.action.START"
        const val ActionStop = "com.haramveil.service.action.STOP"
        const val HeartbeatIntervalMs = 30_000L

        // Must stay under ProtectionBootstrapStore's heartbeat-staleness threshold (2 min),
        // otherwise the health watchdog declares the live service dead and restarts it in a
        // loop while the screen is off.
        const val ScreenOffHeartbeatIntervalMs = 90_000L
        private const val LogTag = "HaramVeilRuntime"
    }

    /**
     * Screen-state tracking for the heartbeat. A wake lock was previously held for this on
     * Samsung devices, but it only lasted 10 minutes (never re-acquired) and keeping the
     * CPU awake defeats Doze for zero protection benefit: no accessibility events arrive
     * while the screen is off anyway.
     */
    private fun registerScreenStateReceiver() {
        if (screenStateReceiver != null) {
            return
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> isScreenOn = true
                    Intent.ACTION_SCREEN_OFF -> isScreenOn = false
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(receiver, filter)
        screenStateReceiver = receiver
    }

    private fun unregisterScreenStateReceiver() {
        val receiver = screenStateReceiver ?: return
        runCatching {
            unregisterReceiver(receiver)
        }
        screenStateReceiver = null
    }
}
