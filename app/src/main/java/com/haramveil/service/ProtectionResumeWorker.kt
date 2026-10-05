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
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.haramveil.data.local.ProtectionPreferencesRepository
import com.haramveil.utils.DebugLog
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit
import kotlin.math.max

class ProtectionResumeWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        // WorkManager re-throws a failure out of doWork, so nothing may escape here except
        // a CancellationException, which must propagate for structured cancellation.
        return try {
            resumeProtectionIfPauseElapsed(applicationContext)
            Result.success()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            DebugLog.w(applicationContext, LogTag) {
                "Safe-window resume failed: ${error.javaClass.simpleName}: ${error.message}"
            }
            Result.success()
        }
    }

    companion object {
        const val UniqueWorkName = "haramveil_resume_protection"
        private const val LogTag = "HaramVeilResume"
    }
}

object ProtectionResumeScheduler {
    fun schedule(
        context: Context,
        delayMs: Long,
    ) {
        val appContext = context.applicationContext
        val effectiveDelayMs = max(delayMs, 0L)
        runCatching {
            ProtectionBootstrapStore(appContext).setSafeWindowActive(true)
        }
        val request = OneTimeWorkRequestBuilder<ProtectionResumeWorker>()
            .setInitialDelay(effectiveDelayMs, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(appContext)
            .enqueueUniqueWork(
                ProtectionResumeWorker.UniqueWorkName,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        // WorkManager jobs and alarms are both dropped on force-stop and on reboot, so the
        // alarm is the only path that can still fire the deadline afterwards. It is inexact
        // and needs no SCHEDULE_EXACT_ALARM permission.
        ServiceHealthAlarmReceiver.scheduleSafeWindowResume(appContext, effectiveDelayMs)
    }

    fun cancel(context: Context) {
        val appContext = context.applicationContext
        WorkManager.getInstance(appContext)
            .cancelUniqueWork(ProtectionResumeWorker.UniqueWorkName)
        ServiceHealthAlarmReceiver.cancelSafeWindowResume(appContext)
        runCatching {
            ProtectionBootstrapStore(appContext).setSafeWindowActive(false)
        }
    }
}

/**
 * Enforces the end of a safe window. Idempotent and shared by every trigger (worker, alarm,
 * foreground service) so the guarantee does not depend on any single scheduler surviving.
 *
 * When the deadline is still in the future the remaining window is re-armed, which repairs
 * clock changes and wakes that arrive early. Once the deadline has passed, protection is
 * resumed and restarted; because [ProtectionPreferencesRepository.resumeProtectionAfterPause]
 * clears the deadline, repeated calls are no-ops.
 *
 * @return true when protection was resumed by this call.
 */
suspend fun resumeProtectionIfPauseElapsed(context: Context): Boolean {
    val appContext = context.applicationContext
    val repository = ProtectionPreferencesRepository(appContext)
    val pauseUntilMs = repository.readSettings().protectionPauseUntilMs
    if (pauseUntilMs <= 0L) {
        return false
    }

    val remainingMs = pauseUntilMs - System.currentTimeMillis()
    if (remainingMs > 0L) {
        ProtectionResumeScheduler.schedule(appContext, remainingMs)
        return false
    }

    repository.resumeProtectionAfterPause()
    // Clear the window bookkeeping without cancelling this very worker (it is already done).
    runCatching {
        ProtectionBootstrapStore(appContext).setSafeWindowActive(false)
    }
    ServiceHealthAlarmReceiver.cancelSafeWindowResume(appContext)
    HaramVeilProtectionController.start(appContext)
    return true
}
