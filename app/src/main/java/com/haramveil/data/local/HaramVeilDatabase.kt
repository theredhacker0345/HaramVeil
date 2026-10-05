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

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteException
import android.util.Log
import com.haramveil.security.PinManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SQLiteOpenHelper
import java.time.Instant
import java.time.ZoneId

class HaramVeilDatabase private constructor(
    context: Context,
    passphrase: CharArray,
) {
    private val applicationContext = context.applicationContext
    private val helper = SqlCipherStatsOpenHelper(
        context = applicationContext,
        passphrase = passphrase,
    )
    private val blockEventDao = SqlCipherBlockEventDao(helper)

    fun blockEventDao(): BlockEventDao = blockEventDao

    fun rekey(
        newPinHash: String,
    ) {
        helper.rekey(StatsDatabasePassphrase.passphraseString(applicationContext, newPinHash))
    }

    fun close() {
        helper.close()
    }

    companion object {
        private const val DatabaseName = "haramveil_stats.db"
        private const val LogTag = "HaramVeilStatsDb"

        @Volatile
        private var instance: HaramVeilDatabase? = null

        fun getInstance(
            context: Context,
        ): HaramVeilDatabase =
            instance ?: synchronized(this) {
                val appContext = context.applicationContext
                val pinHash = PinManager(appContext).storedPinHashOrNull()
                    ?: error("Stats database requires an initialized PIN hash before opening.")
                instance ?: HaramVeilDatabase(
                    context = appContext,
                    passphrase = StatsDatabasePassphrase.passphraseChars(appContext, pinHash),
                ).also { database ->
                    instance = database
                }
            }

        // Never throws: a failed live rekey must not break storing the new PIN.
        fun rekeyIfNeeded(
            context: Context,
            oldPinHash: String?,
            newPinHash: String,
        ) {
            synchronized(this) {
                val appContext = context.applicationContext
                if (!appContext.getDatabasePath(DatabaseName).exists()) {
                    return
                }

                runCatching {
                    val activeInstance = instance
                    if (activeInstance != null) {
                        activeInstance.rekey(newPinHash)
                        return@runCatching
                    }

                    val temporaryDatabase = HaramVeilDatabase(
                        context = appContext,
                        passphrase = if (!oldPinHash.isNullOrBlank()) {
                            StatsDatabasePassphrase.passphraseChars(appContext, oldPinHash)
                        } else {
                            StatsDatabasePassphrase.legacyFallbackPassphraseChars(appContext)
                        },
                    )
                    try {
                        temporaryDatabase.rekey(newPinHash)
                    } finally {
                        temporaryDatabase.close()
                    }
                }.onFailure { throwable ->
                    Log.e(LogTag, "Stats rekey failed; recreating an unreadable stats database.", throwable)
                    discardUnreadableDatabase(appContext)
                }
            }
        }

        // An unreadable database would fail every later access, so it is recreated instead.
        private fun discardUnreadableDatabase(context: Context) {
            val staleInstance = instance
            if (staleInstance != null) {
                runCatching { staleInstance.close() }
                    .onFailure { throwable -> Log.e(LogTag, "Closing the stats database failed.", throwable) }
                instance = null
            }

            runCatching { context.deleteDatabase(DatabaseName) }
                .onFailure { throwable -> Log.e(LogTag, "Deleting the stats database failed.", throwable) }
        }
    }
}

private class SqlCipherStatsOpenHelper(
    context: Context,
    passphrase: CharArray,
) : SQLiteOpenHelper(
    context,
    DatabaseName,
    null,
    DatabaseVersion,
) {
    private val applicationContext = context.applicationContext
    private val passphraseLock = Any()

    // Guarded so rekey() can swap the key without a later reopen using the stale one.
    private var storedPassphrase: CharArray = passphrase.copyOf()

    @Volatile
    private var nativeLibsLoaded = false

    init {
        passphrase.fill('\u0000')
    }

    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS block_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                package_name TEXT NOT NULL,
                app_name TEXT NOT NULL,
                trigger_mode INTEGER NOT NULL,
                detection_detail TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                lockdown_duration_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS index_block_events_timestamp ON block_events(timestamp DESC)",
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS index_block_events_mode ON block_events(trigger_mode)",
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS index_block_events_package ON block_events(package_name)",
        )
    }

    // Upgrades must never drop recorded history: onCreate() is idempotent.
    override fun onUpgrade(
        database: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) {
        if (oldVersion < newVersion) {
            onCreate(database)
        }
    }

    fun openReadable(): SQLiteDatabase {
        ensureNativeLibsLoaded()
        val passphraseCopy = passphraseCopy()
        return try {
            getReadableDatabase(passphraseCopy)
        } finally {
            passphraseCopy.fill('\u0000')
        }
    }

    fun openWritable(): SQLiteDatabase {
        ensureNativeLibsLoaded()
        val passphraseCopy = passphraseCopy()
        return try {
            getWritableDatabase(passphraseCopy)
        } finally {
            passphraseCopy.fill('\u0000')
        }
    }

    fun rekey(
        newPassphrase: String,
    ) {
        ensureNativeLibsLoaded()
        val newPassphraseChars = newPassphrase.toCharArray()
        try {
            openWritable().changePassword(newPassphraseChars)
            replaceStoredPassphrase(newPassphraseChars)
        } finally {
            newPassphraseChars.fill('\u0000')
        }
    }

    override fun close() {
        replaceStoredPassphrase(CharArray(0))
        super.close()
    }

    private fun ensureNativeLibsLoaded() {
        if (nativeLibsLoaded) {
            return
        }
        synchronized(this) {
            if (nativeLibsLoaded) {
                return
            }
            SQLiteDatabase.loadLibs(applicationContext)
            nativeLibsLoaded = true
        }
    }

    private fun passphraseCopy(): CharArray = synchronized(passphraseLock) { storedPassphrase.copyOf() }

    private fun replaceStoredPassphrase(newPassphrase: CharArray) {
        synchronized(passphraseLock) {
            storedPassphrase.fill('\u0000')
            storedPassphrase = newPassphrase.copyOf()
        }
    }

    companion object {
        private const val DatabaseName = "haramveil_stats.db"
        private const val DatabaseVersion = 1
    }
}

// Construction is side-effect free: SQLCipher is opened lazily on an IO dispatcher.
private class SqlCipherBlockEventDao(
    private val helper: SqlCipherStatsOpenHelper,
) : BlockEventDao {
    private val snapshotMutex = Mutex()
    private val eventsState = MutableStateFlow<List<BlockEvent>?>(null)

    // Never published and never thrown: the next collection or write retries the read.
    @Volatile
    private var loadFailed: Throwable? = null

    override suspend fun insertEvent(event: BlockEvent) {
        val values = ContentValues().apply {
            put("package_name", event.packageName)
            put("app_name", event.appName)
            put("trigger_mode", event.triggerMode)
            put("detection_detail", event.detectionDetail)
            put("timestamp", event.timestamp)
            put("lockdown_duration_ms", event.lockdownDurationMs)
        }
        writeAndRefresh { database ->
            database.insert("block_events", null, values)
        }
    }

    override fun getAll(): Flow<List<BlockEvent>> = snapshots()

    override fun getToday(): Flow<List<BlockEvent>> = snapshots().map { events -> eventsInToday(events) }

    override fun getThisWeek(): Flow<List<BlockEvent>> = snapshots().map { events -> eventsInThisWeek(events) }

    override fun getMostBlockedApp(): Flow<String?> = snapshots().map { events -> mostBlockedRecord(events)?.appName }

    override fun getMostBlockedAppRecord(): Flow<MostBlockedAppRecord?> = snapshots().map(::mostBlockedRecord)

    override fun getCountByMode(mode: Int): Flow<Int> = snapshots().map { events ->
        events.count { event -> event.triggerMode == mode }
    }

    override suspend fun deleteAll() {
        writeAndRefresh { database ->
            database.delete("block_events", null, null)
        }
    }

    override suspend fun deleteOlderThan(cutoffTimestampMs: Long): Int {
        var deletedRows = 0
        writeAndRefresh { database ->
            deletedRows = database.delete(
                "block_events",
                "timestamp < ?",
                arrayOf(cutoffTimestampMs.toString()),
            )
        }
        return deletedRows
    }

    private fun snapshots(): Flow<List<BlockEvent>> = eventsState
        .filterNotNull()
        .onStart { refreshSnapshot() }
        .flowOn(Dispatchers.IO)

    private suspend fun refreshSnapshot() {
        snapshotMutex.withLock {
            if (loadFailed == null && eventsState.value != null) {
                return
            }
            reloadSnapshotLocked()
        }
    }

    private suspend fun writeAndRefresh(write: (SQLiteDatabase) -> Unit) {
        withContext(Dispatchers.IO) {
            snapshotMutex.withLock {
                val database = openDatabase(readOnly = false) ?: return@withLock
                try {
                    write(database)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    recordLoadFailure(throwable)
                }
                reloadSnapshotLocked()
            }
        }
    }

    // Must be called while holding snapshotMutex so a stale snapshot can never be published.
    private fun reloadSnapshotLocked() {
        val database = openDatabase(readOnly = true) ?: return
        val events = try {
            readEvents(database)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            recordLoadFailure(throwable)
            return
        }

        loadFailed = null
        eventsState.value = events
    }

    private fun openDatabase(readOnly: Boolean): SQLiteDatabase? {
        val database = try {
            if (readOnly) {
                helper.openReadable()
            } else {
                helper.openWritable()
            }
        } catch (throwable: Throwable) {
            recordLoadFailure(throwable)
            return null
        }

        loadFailed = null
        return database
    }

    private fun recordLoadFailure(throwable: Throwable) {
        val previousFailure = loadFailed
        loadFailed = throwable
        if (previousFailure == null || previousFailure.message != throwable.message) {
            Log.e(LogTag, "Stats database read failed; retrying on the next access.", throwable)
        }
    }

    private fun readEvents(database: SQLiteDatabase): List<BlockEvent> = database.rawQuery(
        """
        SELECT id, package_name, app_name, trigger_mode, detection_detail, timestamp, lockdown_duration_ms
        FROM block_events
        ORDER BY timestamp DESC, id DESC
        """.trimIndent(),
        emptyArray(),
    ).useCursor { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    BlockEvent(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        packageName = cursor.getString(cursor.getColumnIndexOrThrow("package_name")),
                        appName = cursor.getString(cursor.getColumnIndexOrThrow("app_name")),
                        triggerMode = cursor.getInt(cursor.getColumnIndexOrThrow("trigger_mode")),
                        detectionDetail = cursor.getString(cursor.getColumnIndexOrThrow("detection_detail")),
                        timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
                        lockdownDurationMs = cursor.getLong(cursor.getColumnIndexOrThrow("lockdown_duration_ms")),
                    ),
                )
            }
        }
    }

    private fun eventsInToday(events: List<BlockEvent>): List<BlockEvent> {
        val today = Instant.ofEpochMilli(System.currentTimeMillis())
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        return events.filter { event ->
            Instant.ofEpochMilli(event.timestamp)
                .atZone(ZoneId.systemDefault())
                .toLocalDate() == today
        }
    }

    private fun eventsInThisWeek(events: List<BlockEvent>): List<BlockEvent> {
        val cutoffDate = Instant.ofEpochMilli(System.currentTimeMillis())
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .minusDays(6)
        return events.filter { event ->
            !Instant.ofEpochMilli(event.timestamp)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .isBefore(cutoffDate)
        }
    }

    private fun mostBlockedRecord(
        events: List<BlockEvent>,
    ): MostBlockedAppRecord? {
        return events
            .groupBy { event -> event.packageName to event.appName }
            .maxWithOrNull(
                compareBy<Map.Entry<Pair<String, String>, List<BlockEvent>>> { entry -> entry.value.size }
                    .thenBy { entry -> entry.value.maxOfOrNull(BlockEvent::timestamp) ?: 0L },
            )
            ?.let { entry ->
                MostBlockedAppRecord(
                    packageName = entry.key.first,
                    appName = entry.key.second,
                    blockCount = entry.value.size,
                )
            }
    }

    private companion object {
        private const val LogTag = "HaramVeilStatsDao"
    }
}

private inline fun <T> Cursor.useCursor(
    block: (Cursor) -> T,
): T {
    return try {
        block(this)
    } finally {
        try {
            close()
        } catch (_: SQLiteException) {
        }
    }
}