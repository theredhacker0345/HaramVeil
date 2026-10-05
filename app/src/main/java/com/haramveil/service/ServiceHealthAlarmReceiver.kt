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

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.haramveil.utils.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Single alarm entry point for the protection runtime.
 *
 * The safe-window resume and the deferred-restart retry are delivered through this already
 * declared receiver with explicit intents, so no manifest change is needed.
 */
class ServiceHealthAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            ActionSafeWindowResume -> dispatch(context, intent.action) {
                resumeProtectionIfPauseElapsed(context.applicationContext)
            }

            ActionDeferredRestart -> dispatch(context, intent.action) {
                if (protectionShouldRun(context.applicationContext) &&
                    !ProtectionBootstrapStore(context.applicationContext).isServiceAlive()
                ) {
                    HaramVeilProtectionController.start(context.applicationContext)
                }
            }

            ActionServiceHealthCheck -> dispatch(context, intent.action) {
                val appContext = context.applicationContext
                if (!protectionShouldRun(appContext)) {
                    ServiceHealthAlarmReceiver.cancel(appContext)
                    return@dispatch
                }
                if (!ProtectionBootstrapStore(appContext).isServiceAlive()) {
                    HaramVeilProtectionController.start(appContext)
                }
                // Alarms are cleared on reboot and force-stop; keep the cadence alive as
                // long as protection is supposed to be running.
                ServiceHealthAlarmReceiver.schedule(appContext)
            }

            else -> Unit
        }
    }

    /**
     * Runs [block] off the main thread while holding the broadcast open. `goAsync` is
     * required because every action performs disk reads (DataStore/SharedPreferences).
     */
    private fun dispatch(
        context: Context,
        action: String?,
        block: suspend CoroutineScope.() -> Unit,
    ) {
        val appContext = context.applicationContext
        val pendingResult = runCatching { goAsync() }.getOrNull() ?: return
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                DebugLog.w(appContext, LogTag) {
                    "Alarm $action handling failed: ${error.javaClass.simpleName}: ${error.message}"
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun protectionShouldRun(context: Context): Boolean {
        val bootstrapStore = runCatching { ProtectionBootstrapStore(context) }.getOrNull() ?: return false
        return runCatching {
            bootstrapStore.isBootstrapReady() && bootstrapStore.isProtectionEnabled()
        }.getOrDefault(false)
    }

    companion object {
        const val ActionServiceHealthCheck = "com.haramveil.service.ACTION_SERVICE_HEALTH_CHECK"

        /** Explicit-intent action for the safe-window deadline, handled by this receiver. */
        const val ActionSafeWindowResume = "com.haramveil.service.ACTION_SAFE_WINDOW_RESUME"

        /** Explicit-intent retry for a foreground service start that was rejected. */
        const val ActionDeferredRestart = "com.haramveil.service.ACTION_DEFERRED_RESTART"

        private const val LogTag = "HaramVeilAlarm"
        private const val DefaultHealthCheckIntervalMs = 15 * 60 * 1_000L
        private const val SamsungHealthCheckIntervalMs = 5 * 60 * 1_000L
        private const val DefaultSafeWindowResumeDelayMs = 30_000L
        private const val HealthCheckRequestCode = 4109
        private const val SafeWindowResumeRequestCode = 4110
        private const val DeferredRestartRequestCode = 4111

        private fun healthCheckIntervalMs(): Long {
            val isSamsung = Build.MANUFACTURER.lowercase(Locale.US).contains("samsung")
            return if (isSamsung) SamsungHealthCheckIntervalMs else DefaultHealthCheckIntervalMs
        }

        fun schedule(
            context: Context,
        ) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            val intervalMs = healthCheckIntervalMs()
            runCatching {
                alarmManager.setInexactRepeating(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + intervalMs,
                    intervalMs,
                    healthCheckPendingIntent(context),
                )
            }
        }

        fun cancel(
            context: Context,
        ) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            runCatching { alarmManager.cancel(healthCheckPendingIntent(context)) }
        }

        /**
         * Arms a one-shot alarm for the end of a safe window. It is inexact and permission
         * free on purpose: SCHEDULE_EXACT_ALARM is not declared, and `setAndAllowWhileIdle`
         * still fires in Doze just late.
         */
        fun scheduleSafeWindowResume(
            context: Context,
            delayMs: Long,
        ) {
            scheduleOneShot(
                context = context,
                delayMs = delayMs,
                action = ActionSafeWindowResume,
                requestCode = SafeWindowResumeRequestCode,
            )
        }

        fun cancelSafeWindowResume(
            context: Context,
        ) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            runCatching { alarmManager.cancel(safeWindowResumePendingIntent(context)) }
        }

        /** Retries a rejected foreground service start a few seconds later. */
        fun scheduleDeferredRestart(
            context: Context,
        ) {
            scheduleOneShot(
                context = context,
                delayMs = DefaultSafeWindowResumeDelayMs,
                action = ActionDeferredRestart,
                requestCode = DeferredRestartRequestCode,
            )
        }

        fun cancelDeferredRestart(
            context: Context,
        ) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            runCatching { alarmManager.cancel(deferredRestartPendingIntent(context)) }
        }

        private fun scheduleOneShot(
            context: Context,
            delayMs: Long,
            action: String,
            requestCode: Int,
        ) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            val triggerAtElapsedMs = SystemClock.elapsedRealtime() + maxOf(delayMs, 0L)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                explicitIntent(context, action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            runCatching {
                if (canScheduleExactAlarms(alarmManager)) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAtElapsedMs,
                        pendingIntent,
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAtElapsedMs,
                        pendingIntent,
                    )
                }
            }
        }

        private fun canScheduleExactAlarms(alarmManager: AlarmManager): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alarmManager.canScheduleExactAlarms()
            } else {
                true
            }

        private fun explicitIntent(
            context: Context,
            action: String,
        ): Intent =
            Intent(context, ServiceHealthAlarmReceiver::class.java).apply {
                this.action = action
            }

        private fun healthCheckPendingIntent(
            context: Context,
        ): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                HealthCheckRequestCode,
                explicitIntent(context, ActionServiceHealthCheck),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun safeWindowResumePendingIntent(
            context: Context,
        ): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                SafeWindowResumeRequestCode,
                explicitIntent(context, ActionSafeWindowResume),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun deferredRestartPendingIntent(
            context: Context,
        ): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                DeferredRestartRequestCode,
                explicitIntent(context, ActionDeferredRestart),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}