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
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import com.haramveil.overlay.VeilOverlayController
import com.haramveil.utils.DebugLog

object HaramVeilProtectionController {
    /**
     * Starts the protection service, tolerating a rejected foreground service start.
     *
     * Callers are boot, alarm, worker and device-admin entry points: on API 31+ all of them
     * run in the background, where `startForegroundService` throws
     * `ForegroundServiceStartNotAllowedException` whenever no exemption applies. The start is
     * then deferred to the permission-free inexact health alarm instead of crashing.
     */
    fun start(
        context: Context,
    ) {
        val appContext = context.applicationContext
        val started = runCatching {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, HaramVeilForegroundService::class.java).apply {
                    action = HaramVeilForegroundService.ActionStart
                },
            )
        }.isSuccess
        if (!started) {
            DebugLog.w(appContext, LogTag) {
                "Protection service start was rejected; deferring to the health alarm."
            }
            ServiceHealthAlarmReceiver.scheduleDeferredRestart(appContext)
        }
    }

    fun stop(
        context: Context,
    ) {
        val appContext = context.applicationContext
        runCatching {
            appContext.startService(
                Intent(appContext, HaramVeilForegroundService::class.java).apply {
                    action = HaramVeilForegroundService.ActionStop
                },
            )
        }
        VeilOverlayController.stop(appContext)
        ServiceHealthAlarmReceiver.cancel(appContext)
        ServiceHealthAlarmReceiver.cancelDeferredRestart(appContext)
        WorkManager.getInstance(appContext)
            .cancelUniqueWork(ProtectionHealthWorker.UniqueWorkName)
    }

    private const val LogTag = "HaramVeilRuntime"
}
