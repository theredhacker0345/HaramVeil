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

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.haramveil.accessibility.isHaramVeilAccessibilityServiceEnabled
import com.haramveil.data.local.ProtectionPreferencesRepository
import com.haramveil.utils.DebugLog
import com.haramveil.utils.RootAccessHelper
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class ProtectionHealthWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        // WorkManager re-throws a failure out of doWork, so nothing may escape here except
        // a CancellationException, which must propagate for structured cancellation.
        return try {
            runHealthCheck(applicationContext)
            Result.success()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            DebugLog.w(applicationContext, LogTag) {
                "Protection health check failed: ${error.javaClass.simpleName}: ${error.message}"
            }
            Result.success()
        }
    }

    private suspend fun runHealthCheck(context: Context) {
        val bootstrapStore = ProtectionBootstrapStore(context)
        if (!bootstrapStore.isBootstrapReady()) {
            return
        }

        val repository = ProtectionPreferencesRepository(context)
        val settings = repository.readSettings()
        if (settings.protectionPauseUntilMs > 0L) {
            // Safe window: the shared helper re-arms the alarm and the one-shot worker for
            // the remaining window and resumes protection once the deadline passes.
            resumeProtectionIfPauseElapsed(context)
            return
        }

        if (!bootstrapStore.isProtectionEnabled()) {
            return
        }

        if (!bootstrapStore.isServiceAlive()) {
            HaramVeilProtectionController.start(context)
        }

        if (!isHaramVeilAccessibilityServiceEnabled(context)) {
            if (RootAccessHelper.isRootAvailable()) {
                RootAccessHelper.tryRestoreAccessibilityService(context)
            }
            if (!isHaramVeilAccessibilityServiceEnabled(context)) {
                ProtectionNotificationHelper.showAccessibilityReenablePrompt(context)
            }
        }
    }

    companion object {
        const val UniqueWorkName = "haramveil_protection_health"

        private const val LogTag = "HaramVeilHealth"

        fun enqueue(
            context: Context,
        ) {
            val request = PeriodicWorkRequestBuilder<ProtectionHealthWorker>(
                repeatInterval = 1,
                repeatIntervalTimeUnit = TimeUnit.HOURS,
            ).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    UniqueWorkName,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request,
                )
        }
    }
}
