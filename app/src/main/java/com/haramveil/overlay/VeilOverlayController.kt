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

package com.haramveil.overlay

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.haramveil.service.ServiceHealthAlarmReceiver
import com.haramveil.utils.DebugLog

object VeilOverlayController {
    /**
     * Starts the veil service, tolerating a rejected foreground service start.
     *
     * This is called from the accessibility service `onServiceConnected`, i.e. from a
     * binder context where API 31+ throws `ForegroundServiceStartNotAllowedException`
     * whenever the app is in the background without an exemption. A rejected start must
     * never crash the accessibility service, so it is deferred to the permission-free
     * inexact health alarm instead.
     */
    fun start(
        context: Context,
    ) {
        val appContext = context.applicationContext
        val started = runCatching {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, VeilOverlayService::class.java).apply {
                    action = VeilOverlayService.ActionStart
                },
            )
        }.isSuccess
        if (!started) {
            DebugLog.w(appContext, LogTag) {
                "Veil overlay service start was rejected; deferring to the health alarm."
            }
            ServiceHealthAlarmReceiver.scheduleDeferredRestart(appContext)
        }
    }

    fun stop(
        context: Context,
    ) {
        runCatching {
            context.applicationContext.stopService(
                Intent(context.applicationContext, VeilOverlayService::class.java),
            )
        }
    }

    private const val LogTag = "HaramVeilVeil"
}
