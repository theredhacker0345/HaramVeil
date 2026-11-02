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

package com.haramveil.security

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.haramveil.data.models.ActiveLockdown

class AppLockdownManager(
    context: Context,
) {
    private val applicationContext = context.applicationContext
    private val encryptedPreferences by lazy {
        EncryptedPrefsFactory.create(applicationContext, PreferenceFileName)
    }

    fun isLocked(
        packageName: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Boolean = isLockActive(packageName, nowEpochMs)

    // A lockdown only ends once the wall-clock AND the monotonic deadline have both passed
    // within the same boot, so moving the device date forward cannot cancel an active lockdown.
    fun isLockActive(
        packageName: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val record = loadRecord(packageName) ?: return false

        val remainingMs = computeRemainingLockdownMs(
            record = record,
            nowEpochMs = nowEpochMs,
        )
        if (remainingMs <= 0L) {
            unlockApp(packageName)
            return false
        }
        return true
    }

    fun lockApp(
        packageName: String,
        durationMs: Long,
        nowEpochMs: Long = System.currentTimeMillis(),
    ) {
        val effectiveDurationMs = durationMs.coerceAtLeast(MinimumLockdownDurationMs)
        encryptedPreferences.edit()
            .putLong(lockKey(packageName), nowEpochMs + effectiveDurationMs)
            .putLong(elapsedLockKey(packageName), SystemClock.elapsedRealtime() + effectiveDurationMs)
            .putInt(lockBootCountKey(packageName), currentBootCount())
            .apply()
        invalidateRecord(packageName)
    }

    fun unlockApp(
        packageName: String,
    ) {
        encryptedPreferences.edit()
            .remove(lockKey(packageName))
            .remove(elapsedLockKey(packageName))
            .remove(lockBootCountKey(packageName))
            .apply()
        invalidateRecord(packageName)
    }

    fun activeLockdowns(
        nowEpochMs: Long = System.currentTimeMillis(),
    ): List<ActiveLockdown> = encryptedPreferences.all.keys
        .filter { key -> key.startsWith(LockPrefix) }
        .map { key -> key.removePrefix(LockPrefix) }
        .filter { packageName -> isLockActive(packageName, nowEpochMs) }
        .map { packageName ->
            ActiveLockdown(
                packageName = packageName,
                expiresAtEpochMs = encryptedPreferences.getLong(lockKey(packageName), 0L),
            )
        }
        .sortedBy { lockdown -> lockdown.expiresAtEpochMs }

    private fun computeRemainingLockdownMs(
        record: LockdownRecord,
        nowEpochMs: Long,
    ): Long {
        val wallClockRemainingMs = record.wallExpiryEpochMs - nowEpochMs
        val sameBootRemainingMs = record.sameBootRemainingMs()
        if (wallClockRemainingMs <= 0L) {
            return sameBootRemainingMs.coerceAtLeast(0L)
        }
        return maxOf(wallClockRemainingMs, sameBootRemainingMs, 0L)
    }

    /**
     * Per-package record cache with a short TTL. Every TYPE_WINDOW_STATE_CHANGED event
     * calls isLocked() from the accessibility callback; each direct read goes through
     * Jetpack EncryptedSharedPreferences (per-key AES decrypt), which is far too slow to
     * run per event on the main thread. Writes invalidate the record so a fresh lockdown
     * is honoured at once; the TTL keeps cross-instance staleness bounded.
     */
    private fun loadRecord(packageName: String): LockdownRecord? {
        val now = SystemClock.elapsedRealtime()
        companionCache[packageName]?.let { cached ->
            if (now - cached.cachedAtMs < RecordCacheTtlMs) {
                return cached.record
            }
        }

        val expiryTimestamp = encryptedPreferences.getLong(lockKey(packageName), 0L)
        val record = if (expiryTimestamp <= 0L) {
            null
        } else {
            val storedBootCount = encryptedPreferences.getInt(lockBootCountKey(packageName), 0)
            val elapsedUntil = encryptedPreferences.getLong(elapsedLockKey(packageName), 0L)
            val currentBootCount = currentBootCount()
            LockdownRecord(
                wallExpiryEpochMs = expiryTimestamp,
                elapsedUntilMs = if (storedBootCount != 0 && storedBootCount == currentBootCount) {
                    elapsedUntil
                } else {
                    0L
                },
            )
        }
        companionCache[packageName] = CachedRecord(record, now)
        return record
    }

    private fun invalidateRecord(packageName: String) {
        companionCache.remove(packageName)
    }

    private fun LockdownRecord.sameBootRemainingMs(): Long {
        if (elapsedUntilMs == 0L) {
            return 0L
        }
        return elapsedUntilMs - SystemClock.elapsedRealtime()
    }

    private fun currentBootCount(): Int =
        runCatching {
            Settings.Global.getInt(applicationContext.contentResolver, Settings.Global.BOOT_COUNT)
        }.getOrDefault(0)

    private fun lockKey(packageName: String): String = "$LockPrefix$packageName"

    private fun elapsedLockKey(packageName: String): String = "$ElapsedLockPrefix$packageName"

    private fun lockBootCountKey(packageName: String): String = "$BootCountLockPrefix$packageName"

    private data class LockdownRecord(
        val wallExpiryEpochMs: Long,
        val elapsedUntilMs: Long,
    )

    private data class CachedRecord(
        val record: LockdownRecord?,
        val cachedAtMs: Long,
    )

    private companion object {
        const val PreferenceFileName = "haramveil_lockdowns"
        const val LockPrefix = "lockdown_"
        // Deliberately not prefixed with LockPrefix so activeLockdowns only reads real lockdowns.
        const val ElapsedLockPrefix = "monotonic_lockdown_elapsed_"
        const val BootCountLockPrefix = "monotonic_lockdown_boot_"
        const val MinimumLockdownDurationMs = 60_000L

        const val RecordCacheTtlMs = 2_000L

        // Shared across instances: lockdowns are written by the accessibility service and
        // read by the overlay/UI, so a per-instance cache could serve stale state.
        val companionCache = java.util.concurrent.ConcurrentHashMap<String, CachedRecord>()
    }
}
