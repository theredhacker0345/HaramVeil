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
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

internal class EncryptedPrefsUnavailableException(
    cause: Throwable,
) : IllegalStateException("Encrypted preferences could not be opened.", cause)

internal object EncryptedPrefsFactory {
    private const val LogTag = "HaramVeilSecurePrefs"
    private const val MasterKeyAlias = "haramveil.master_key.v1"

    // Never falls back to plaintext storage: a keystore failure fails closed and says why.
    fun create(
        context: Context,
        preferenceFileName: String,
    ): SharedPreferences =
        runCatching {
            val masterKey = MasterKey.Builder(context.applicationContext, MasterKeyAlias)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context.applicationContext,
                preferenceFileName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse { throwable ->
            Log.e(
                LogTag,
                "Keystore-backed prefs '$preferenceFileName' are unavailable; refusing plaintext storage.",
                throwable,
            )
            throw EncryptedPrefsUnavailableException(throwable)
        }
}
