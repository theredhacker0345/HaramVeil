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
import java.util.concurrent.TimeUnit
import kotlin.math.max

class ProtectionResumeWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val repository = ProtectionPreferencesRepository(context)
        val settings = repository.readSettings()
        val pauseUntilMs = settings.protectionPauseUntilMs
        if (pauseUntilMs <= 0L) {
            return Result.success()
        }

        val nowMs = System.currentTimeMillis()
        if (nowMs < pauseUntilMs) {
            ProtectionResumeScheduler.schedule(context, pauseUntilMs - nowMs)
            return Result.success()
        }

        repository.resumeProtectionAfterPause()
        HaramVeilProtectionController.start(context)
        return Result.success()
    }

    companion object {
        const val UniqueWorkName = "haramveil_resume_protection"
    }
}

object ProtectionResumeScheduler {
    fun schedule(
        context: Context,
        delayMs: Long,
    ) {
        val request = OneTimeWorkRequestBuilder<ProtectionResumeWorker>()
            .setInitialDelay(max(delayMs, 0L), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                ProtectionResumeWorker.UniqueWorkName,
                ExistingWorkPolicy.REPLACE,
                request,
            )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context)
            .cancelUniqueWork(ProtectionResumeWorker.UniqueWorkName)
    }
}
