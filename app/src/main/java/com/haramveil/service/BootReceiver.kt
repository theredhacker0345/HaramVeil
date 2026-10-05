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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.haramveil.utils.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action
        if (action !in SupportedActions) {
            return
        }

        val appContext = context.applicationContext
        val pendingResult = runCatching { goAsync() }.getOrNull() ?: return
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                startProtectionAfterBoot(appContext, action)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                DebugLog.w(appContext, LogTag) {
                    "Boot handling for $action failed: ${error.javaClass.simpleName}: ${error.message}"
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun startProtectionAfterBoot(
        appContext: Context,
        action: String?,
    ) {
        // Only the direct-boot broadcast can arrive while the credential-encrypted
        // storage is still locked; everything else must stay on the app context so a
        // failure can never be reported against the wrong storage.
        val bootContext = if (action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            val deviceProtectedContext = runCatching {
                appContext.createDeviceProtectedStorageContext()
            }.getOrElse { error ->
                DebugLog.w(appContext, LogTag) {
                    "Device-protected storage unavailable at direct boot: ${error.javaClass.simpleName}"
                }
                return
            }
            deviceProtectedContext
        } else {
            appContext
        }

        val bootstrapStore = runCatching { ProtectionBootstrapStore(bootContext) }.getOrNull() ?: return
        val shouldStart = runCatching {
            bootstrapStore.isBootstrapReady() && bootstrapStore.isProtectionEnabled()
        }.getOrElse { error ->
            DebugLog.w(appContext, LogTag) {
                "Bootstrap read failed: ${error.javaClass.simpleName}: ${error.message}"
            }
            false
        }
        if (!shouldStart) {
            return
        }

        // The foreground start itself may still be rejected in the background, which the
        // controller turns into a deferred health-alarm retry.
        HaramVeilProtectionController.start(bootContext)
    }

    private companion object {
        const val LogTag = "HaramVeilBoot"

        val SupportedActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}