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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration

class StatsRepository private constructor(
    context: Context,
) {
    private val applicationContext = context.applicationContext
    private val packageManager = applicationContext.packageManager

    // The database requires a PIN hash to open (SQLCipher). If no PIN is set yet
    // (e.g., user cleared app data but accessibility service is still enabled),
    // we gracefully degrade to a no-op mode instead of crashing.
    private val dao: BlockEventDao? = runCatching {
        HaramVeilDatabase.getInstance(applicationContext).blockEventDao()
    }.getOrElse { null }

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val hasLoadedSnapshotMutable = MutableStateFlow(false)

    val allEvents: StateFlow<List<BlockEvent>> = (dao?.getAll() ?: kotlinx.coroutines.flow.flowOf(emptyList()))
        .stateIn(repositoryScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val todayEvents: StateFlow<List<BlockEvent>> = (dao?.getToday() ?: kotlinx.coroutines.flow.flowOf(emptyList()))
        .stateIn(repositoryScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val thisWeekEvents: StateFlow<List<BlockEvent>> = (dao?.getThisWeek() ?: kotlinx.coroutines.flow.flowOf(emptyList()))
        .stateIn(repositoryScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val allTimeCount: StateFlow<Int> = allEvents
        .map { events -> events.size }
        .stateIn(repositoryScope, SharingStarted.WhileSubscribed(5_000L), 0)

    val todayCount: StateFlow<Int> = todayEvents
        .map { events -> events.size }
        .stateIn(repositoryScope, SharingStarted.WhileSubscribed(5_000L), 0)

    val thisWeekCount: StateFlow<Int> = thisWeekEvents
        .map { events -> events.size }
        .stateIn(repositoryScope, SharingStarted.WhileSubscribed(5_000L), 0)

    val mostBlockedAppName: StateFlow<String?> = (dao?.getMostBlockedApp() ?: kotlinx.coroutines.flow.flowOf(null))
        .stateIn(repositoryScope, SharingStarted.WhileSubscribed(5_000L), null)

    val mostBlockedAppRecord: StateFlow<MostBlockedAppRecord?> = (dao?.getMostBlockedAppRecord() ?: kotlinx.coroutines.flow.flowOf(null))
        .stateIn(repositoryScope, SharingStarted.WhileSubscribed(5_000L), null)

    val hasLoadedSnapshot: StateFlow<Boolean> = hasLoadedSnapshotMutable.asStateFlow()

    init {
        if (dao != null) {
            repositoryScope.launch {
                dao.getAll().first()
                hasLoadedSnapshotMutable.value = true
            }
        } else {
            hasLoadedSnapshotMutable.value = true
        }
    }

    suspend fun logBlock(
        packageName: String,
        mode: Int,
        detail: String,
        lockdownMs: Long,
        appName: String? = null,
    ) {
        val activeDao = dao ?: return
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
    ): StateFlow<Int> {
        val flow = dao?.getCountByMode(mode.coerceIn(minimumValue = 1, maximumValue = 3))
            ?: kotlinx.coroutines.flow.flowOf(0)
        return flow.stateIn(repositoryScope, SharingStarted.WhileSubscribed(5_000L), 0)
    }

    suspend fun clearHistory() {
        dao?.deleteAll()
    }

    suspend fun cleanupEventsOlderThan(
        retentionDays: Int,
    ): Int {
        val activeDao = dao ?: return 0
        val cutoffTimestamp = System.currentTimeMillis() - Duration.ofDays(retentionDays.toLong()).toMillis()
        return activeDao.deleteOlderThan(cutoffTimestamp)
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
