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
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration

class StatsRepository private constructor(
    context: Context,
) {
    private val applicationContext = context.applicationContext
    private val packageManager = applicationContext.packageManager

    // The database can only open once a PIN hash exists (SQLCipher). It stays null until then
    // and is retried, so a service started before onboarding still reports real numbers later.
    private val daoState = MutableStateFlow<BlockEventDao?>(null)
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val hasLoadedSnapshotMutable = MutableStateFlow(false)

    @Volatile
    private var lastDaoFailureMessage: String? = null

    private val allEventsSource = daoFlow<List<BlockEvent>>(fallback = emptyList()) { dao -> dao.getAll() }
    private val todayEventsSource = daoFlow<List<BlockEvent>>(fallback = emptyList()) { dao -> dao.getToday() }
    private val thisWeekEventsSource =
        daoFlow<List<BlockEvent>>(fallback = emptyList()) { dao -> dao.getThisWeek() }
    private val mostBlockedAppNameSource = daoFlow<String?>(fallback = null) { dao -> dao.getMostBlockedApp() }
    private val mostBlockedAppRecordSource =
        daoFlow<MostBlockedAppRecord?>(fallback = null) { dao -> dao.getMostBlockedAppRecord() }

    val allEvents: StateFlow<List<BlockEvent>> = allEventsSource
        .stateIn(repositoryScope, SharingStarted.WhileSubscribed(StopTimeoutMs), emptyList())

    val todayEvents: StateFlow<List<BlockEvent>> = todayEventsSource
        .stateIn(repositoryScope, SharingStarted.WhileSubscribed(StopTimeoutMs), emptyList())

    val thisWeekEvents: StateFlow<List<BlockEvent>> = thisWeekEventsSource
        .stateIn(repositoryScope, SharingStarted.WhileSubscribed(StopTimeoutMs), emptyList())

    val allTimeCount: StateFlow<Int> = allEvents
        .map { events -> events.size }
        .stateIn(repositoryScope, SharingStarted.WhileSubscribed(StopTimeoutMs), 0)

    val todayCount: StateFlow<Int> = todayEvents
        .map { events -> events.size }
        .stateIn(repositoryScope, SharingStarted.WhileSubscribed(StopTimeoutMs), 0)

    val thisWeekCount: StateFlow<Int> = thisWeekEvents
        .map { events -> events.size }
        .stateIn(repositoryScope, SharingStarted.WhileSubscribed(StopTimeoutMs), 0)

    val mostBlockedAppName: StateFlow<String?> = mostBlockedAppNameSource
        .stateIn(repositoryScope, SharingStarted.WhileSubscribed(StopTimeoutMs), null)

    val mostBlockedAppRecord: StateFlow<MostBlockedAppRecord?> = mostBlockedAppRecordSource
        .stateIn(repositoryScope, SharingStarted.WhileSubscribed(StopTimeoutMs), null)

    val hasLoadedSnapshot: StateFlow<Boolean> = hasLoadedSnapshotMutable.asStateFlow()

    init {
        repositoryScope.launch { resolveDaoWithRetry() }
        repositoryScope.launch { awaitFirstSnapshot() }
    }

    suspend fun logBlock(
        packageName: String,
        mode: Int,
        detail: String,
        lockdownMs: Long,
        appName: String? = null,
    ) {
        val activeDao = daoState.value ?: resolveDaoOrNull() ?: return
        activeDao.insertEvent(
            BlockEvent(
                packageName = packageName,
                appName = resolveAppName(packageName, appName),
                triggerMode = mode.coerceIn(minimumValue = 1, maximumValue = 3),
                detectionDetail = detail,
                timestamp = System.currentTimeMillis(),
                lockdownDurationMs = lockdownMs,
            ),
        )
    }

    fun countByMode(
        mode: Int,
    ): StateFlow<Int> = daoFlow<Int>(fallback = 0) { dao ->
        dao.getCountByMode(mode.coerceIn(minimumValue = 1, maximumValue = 3))
    }.stateIn(repositoryScope, SharingStarted.WhileSubscribed(StopTimeoutMs), 0)

    suspend fun clearHistory() {
        (daoState.value ?: resolveDaoOrNull())?.deleteAll()
    }

    suspend fun cleanupEventsOlderThan(
        retentionDays: Int,
    ): Int {
        val activeDao = daoState.value ?: resolveDaoOrNull() ?: return 0
        // A corrupt or zero retention must never produce a cutoff in the future.
        val safeRetentionDays = retentionDays.coerceAtLeast(MinimumRetentionDays)
        val cutoffTimestamp = System.currentTimeMillis() - Duration.ofDays(safeRetentionDays.toLong()).toMillis()
        return activeDao.deleteOlderThan(cutoffTimestamp)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> daoFlow(
        fallback: T,
        select: (BlockEventDao) -> Flow<T>,
    ): Flow<T> = daoState
        .distinctUntilChanged()
        .flatMapLatest { dao -> dao?.let(select) ?: flowOf(fallback) }

    private suspend fun awaitFirstSnapshot() {
        daoState.filterNotNull().collect { dao ->
            val snapshot = try {
                dao.getAll().first()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                Log.w(LogTag, "Reading the first stats snapshot failed; waiting for a retry.", throwable)
                null
            }
            // Only a real snapshot read may report the stats tab as loaded.
            hasLoadedSnapshotMutable.value = snapshot != null
        }
    }

    private suspend fun resolveDaoWithRetry() {
        while (currentCoroutineContext().isActive) {
            if (resolveDaoOrNull() != null) {
                return
            }
            delay(DaoResolveRetryIntervalMs)
        }
    }

    private suspend fun resolveDaoOrNull(): BlockEventDao? {
        daoState.value?.let { dao -> return dao }
        return withContext(Dispatchers.IO) {
            val resolvedDao = try {
                HaramVeilDatabase.getInstance(applicationContext).blockEventDao()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                recordDaoFailure(throwable)
                null
            }

            resolvedDao?.also { dao -> daoState.value = dao }
        }
    }

    private fun recordDaoFailure(throwable: Throwable) {
        if (lastDaoFailureMessage == throwable.message) {
            return
        }
        lastDaoFailureMessage = throwable.message
        Log.w(LogTag, "Stats database is not available yet; retrying every ${RetryIntervalSeconds}s.", throwable)
    }

    private fun resolveAppName(
        packageName: String,
        fallbackName: String?,
    ): String {
        if (!fallbackName.isNullOrBlank()) {
            return fallbackName
        }

        return runCatching {
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(applicationInfo).toString()
        }.getOrElse {
            packageName.substringAfterLast('.')
                .replace('_', ' ')
                .replace('-', ' ')
                .replaceFirstChar { character ->
                    if (character.isLowerCase()) {
                        character.titlecase()
                    } else {
                        character.toString()
                    }
                }
        }
    }

    companion object {
        private const val LogTag = "HaramVeilStats"
        private const val MinimumRetentionDays = 1
        private const val DaoResolveRetryIntervalMs = 5_000L
        private const val RetryIntervalSeconds = 5
        private const val StopTimeoutMs = 5_000L

        @Volatile
        private var instance: StatsRepository? = null

        fun getInstance(
            context: Context,
        ): StatsRepository =
            instance ?: synchronized(this) {
                instance ?: StatsRepository(context.applicationContext).also { repository ->
                    instance = repository
                }
            }
    }
}