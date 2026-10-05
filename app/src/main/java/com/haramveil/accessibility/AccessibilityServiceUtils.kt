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

package com.haramveil.accessibility

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.haramveil.utils.DebugLog

fun isHaramVeilAccessibilityServiceEnabled(
    context: Context,
): Boolean {
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    val expectedService = ComponentName(
        context.packageName,
        HaramVeilAccessibilityService::class.java.name,
    )
    return enabledServices.split(':').any { serviceName ->
        matchesServiceComponent(serviceName, expectedService)
    }
}

fun openHaramVeilAccessibilitySettings(
    context: Context,
) {
    // Some OEM builds ship without a resolvable accessibility settings activity.
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }.onFailure { error ->
        DebugLog.w(context, LogTag) {
            "Unable to open accessibility settings: ${error.javaClass.simpleName}"
        }
    }
}

private fun matchesServiceComponent(
    serviceName: String,
    expectedService: ComponentName,
): Boolean {
    val trimmedName = serviceName.trim()
    if (trimmedName.isEmpty()) {
        return false
    }
    // The stored value is usually "pkg/.Class", sometimes "pkg/Class" or ":pkg/.Class".
    val flattenedName = trimmedName.removePrefix(":")
    val parsedService = ComponentName.unflattenFromString(flattenedName)
    if (parsedService == null) {
        return flattenedName.equals(other = expectedService.flattenToString(), ignoreCase = true)
    }
    return parsedService.packageName.equals(other = expectedService.packageName, ignoreCase = true) &&
        parsedService.className.equals(other = expectedService.className, ignoreCase = true)
}

private const val LogTag = "HaramVeilA11yUtils"

