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

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.haramveil.service.HaramVeilProtectionController
import com.haramveil.service.ProtectionBootstrapStore
import com.haramveil.service.ProtectionNotificationHelper

class HaramVeilDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(
        context: Context,
        intent: Intent,
    ): CharSequence =
        "Disabling Device Admin removes uninstall resistance and weakens HaramVeil's tamper protection."

    override fun onDisabled(
        context: Context,
        intent: Intent,
    ) {
        // This broadcast runs in the background: prefs writes and posting must never throw here.
        runCatching {
            val warningMessage = "HaramVeil protection has been disabled. Tap to re-enable."
            ProtectionBootstrapStore(context).recordTamperEvent(
                eventType = TamperEventTypeDeviceAdminDisabled,
                message = warningMessage,
            )
            ProtectionNotificationHelper.showDeviceAdminDisabled(context)
        }.onFailure { throwable ->
            Log.e(LogTag, "Recording the device admin disable failed.", throwable)
        }
    }

    override fun onEnabled(
        context: Context,
        intent: Intent,
    ) {
        // A foreground service start from a background broadcast throws on API 31+.
        runCatching { HaramVeilProtectionController.start(context) }
            .onFailure { throwable ->
                Log.e(LogTag, "Protection could not start from device admin enable.", throwable)
            }
    }

    companion object {
        const val TamperEventTypeDeviceAdminDisabled = "device_admin_disabled"
        private const val LogTag = "HaramVeilDeviceAdmin"
    }
}
