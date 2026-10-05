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
        val expiryTimestamp = encryptedPreferences.getLong(lockKey(packageName), 0L)
        if (expiryTimestamp <= 0L) {
            return false
        }

        val remainingMs = computeRemainingLockdownMs(
            packageName = packageName,
            expiryTimestamp = expiryTimestamp,
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
    }

    fun unlockApp(
        packageName: String,
    ) {
        encryptedPreferences.edit()
            .remove(lockKey(packageName))
            .remove(elapsedLockKey(packageName))
            .remove(lockBootCountKey(packageName))
            .apply()
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
        packageName: String,
        expiryTimestamp: Long,
        nowEpochMs: Long,
    ): Long {
        val wallClockRemainingMs = expiryTimestamp - nowEpochMs
        val sameBootRemainingMs = remainingElapsedLockdownMs(packageName)
        if (wallClockRemainingMs <= 0L) {
            return sameBootRemainingMs.coerceAtLeast(0L)
        }
        return maxOf(wallClockRemainingMs, sameBootRemainingMs, 0L)
    }

    private fun remainingElapsedLockdownMs(packageName: String): Long {
        val storedBootCount = encryptedPreferences.getInt(lockBootCountKey(packageName), 0)
        val currentBootCount = currentBootCount()
        if (storedBootCount == 0 || storedBootCount != currentBootCount) {
            return 0L
        }

        val elapsedLockdownUntil = encryptedPreferences.getLong(elapsedLockKey(packageName), 0L)
        if (elapsedLockdownUntil == 0L) {
            return 0L
        }

        return elapsedLockdownUntil - SystemClock.elapsedRealtime()
    }

    private fun currentBootCount(): Int =
        runCatching {
            Settings.Global.getInt(applicationContext.contentResolver, Settings.Global.BOOT_COUNT)
        }.getOrDefault(0)

    private fun lockKey(packageName: String): String = "$LockPrefix$packageName"

    private fun elapsedLockKey(packageName: String): String = "$ElapsedLockPrefix$packageName"

    private fun lockBootCountKey(packageName: String): String = "$BootCountLockPrefix$packageName"

    private companion object {
        const val PreferenceFileName = "haramveil_lockdowns"
        const val LockPrefix = "lockdown_"
        // Deliberately not prefixed with LockPrefix so activeLockdowns only reads real lockdowns.
        const val ElapsedLockPrefix = "monotonic_lockdown_elapsed_"
        const val BootCountLockPrefix = "monotonic_lockdown_boot_"
        const val MinimumLockdownDurationMs = 60_000L
    }
}
