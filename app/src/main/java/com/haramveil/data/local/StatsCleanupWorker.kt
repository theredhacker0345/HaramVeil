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

package com.haramveil.data.local

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class StatsCleanupWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            try {
                val retentionDays = ProtectionPreferencesRepository(applicationContext)
                    .readSettings()
                    .statsRetentionDays
                StatsRepository.getInstance(applicationContext)
                    .cleanupEventsOlderThan(retentionDays)
                Result.success()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                // WorkManager rethrows anything that escapes, so every failure becomes a retry.
                Log.e(LogTag, "Stats cleanup failed; retrying.", throwable)
                Result.retry()
            }
        }

    companion object {
        const val UniqueWorkName = "haramveil_stats_cleanup"
        private const val LogTag = "HaramVeilStatsCleanup"

        fun enqueue(
            context: Context,
        ) {
            val request = PeriodicWorkRequestBuilder<StatsCleanupWorker>(
                repeatInterval = 1,
                repeatIntervalTimeUnit = TimeUnit.DAYS,
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
