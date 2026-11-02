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

package com.haramveil.utils

import android.content.Context
import android.os.Process
import com.haramveil.accessibility.HaramVeilAccessibilityService
import java.io.File
import java.util.concurrent.TimeUnit

object RootAccessHelper {
    private val suCandidates = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system_ext/bin/su",
    )

    private const val AvailabilityCacheTtlMs = 60_000L

    @Volatile
    private var cachedAvailability: Boolean? = null

    @Volatile
    private var cachedAvailabilityAtMs = 0L
    private val availabilityLock = Any()

    /**
     * Root detection spawns a `su -c id` subprocess with a 1.5s timeout. The UI polls this
     * every second while open, and the foreground service calls it on every start, so the
     * verdict is cached for a minute instead of paying a subprocess spawn per check.
     */
    fun isRootAvailable(): Boolean {
        val cached = synchronized(availabilityLock) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (cachedAvailability != null && now - cachedAvailabilityAtMs < AvailabilityCacheTtlMs) {
                cachedAvailability
            } else {
                null
            }
        }
        if (cached != null) {
            return cached
        }

        val available = suCandidates.any { path -> File(path).exists() } &&
            runSuCommand("id", timeoutMs = 1_500L)
        synchronized(availabilityLock) {
            cachedAvailability = available
            cachedAvailabilityAtMs = android.os.SystemClock.elapsedRealtime()
        }
        return available
    }

    fun invalidateAvailabilityCache() {
        synchronized(availabilityLock) {
            cachedAvailability = null
            cachedAvailabilityAtMs = 0L
        }
    }

    fun tryLowerNiceValue(): Boolean =
        runSuCommand(
            command = "renice -n -10 -p ${Process.myPid()}",
            timeoutMs = 1_500L,
        )

    fun tryRestoreAccessibilityService(
        context: Context,
    ): Boolean {
        val serviceId = "${context.packageName}/${HaramVeilAccessibilityService::class.java.name}"
        val command =
            """
            current=${'$'}(settings get secure enabled_accessibility_services)
            case "${'$'}current" in
              *$serviceId*) new_value="${'$'}current" ;;
              null|'') new_value="$serviceId" ;;
              *) new_value="${'$'}current:$serviceId" ;;
            esac
            settings put secure enabled_accessibility_services "${'$'}new_value"
            settings put secure accessibility_enabled 1
            """.trimIndent()
        return runSuCommand(command, timeoutMs = 2_500L)
    }

    private fun runSuCommand(
        command: String,
        timeoutMs: Long,
    ): Boolean {
        if (!suCandidates.any { path -> File(path).exists() }) {
            return false
        }

        return runCatching {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            val succeeded = completed && process.exitValue() == 0
            if (succeeded) {
                // A successful su call is proof of availability; refresh the cache instead
                // of re-probing on the next isRootAvailable() check.
                synchronized(availabilityLock) {
                    cachedAvailability = true
                    cachedAvailabilityAtMs = android.os.SystemClock.elapsedRealtime()
                }
            }
            succeeded
        }.getOrDefault(false)
    }
}
