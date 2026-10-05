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

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.haramveil.data.models.ProtectionSettings
import com.haramveil.data.models.TextRecognitionEngine
import com.haramveil.data.models.VisualModelOption
import com.haramveil.service.ProtectionBootstrapStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

class ProtectionPreferencesRepository(
    private val context: Context,
) {
    val settingsFlow: Flow<ProtectionSettings> = context.haramVeilPreferencesDataStore.data
        .catch { throwable ->
            if (throwable is IOException) {
                emit(emptyPreferences())
            } else {
                throw throwable
            }
        }
        .map { preferences ->
            ProtectionSettings(
                protectionEnabled = preferences[HaramVeilPreferenceKeys.ProtectionEnabled] ?: true,
                protectionPauseUntilMs = preferences[HaramVeilPreferenceKeys.ProtectionPauseUntilMs] ?: 0L,
                monitoredPackages = preferences[HaramVeilPreferenceKeys.SelectedPackages] ?: emptySet(),
                keywordBlocklist = (preferences[HaramVeilPreferenceKeys.KeywordBlocklist]
                    ?: DefaultKeywordBlocklist.entries.toSet())
                    .sorted(),
                mode1Enabled = preferences[HaramVeilPreferenceKeys.Mode1Enabled] ?: true,
                mode2Enabled = preferences[HaramVeilPreferenceKeys.Mode2Enabled] ?: true,
                mode3Enabled = preferences[HaramVeilPreferenceKeys.Mode3Enabled] ?: false,
                mode3ForcedPackages = preferences[HaramVeilPreferenceKeys.Mode3ForcedPackages] ?: emptySet(),
                mode3ForcedSeeded = preferences[HaramVeilPreferenceKeys.Mode3ForcedSeeded] ?: false,
                strictModeEnabled = preferences[HaramVeilPreferenceKeys.StrictModeEnabled] ?: false,
                selectedTextEngine = TextRecognitionEngine.fromStorageValue(
                    preferences[HaramVeilPreferenceKeys.SelectedTextEngine],
                ) ?: TextRecognitionEngine.ML_KIT,
                selectedVisualModel = VisualModelOption.fromStorageValue(
                    preferences[HaramVeilPreferenceKeys.SelectedVisualModel],
                ),
                domainBlocklist = (preferences[HaramVeilPreferenceKeys.DomainBlocklist]
                    ?: DefaultDomainBlocklist.entries.toSet())
                    .sorted(),
                frameSkipIntervalMs = (preferences[HaramVeilPreferenceKeys.FrameSkipIntervalMs]
                    ?: HaramVeilPreferenceKeys.DefaultFrameSkipIntervalMs)
                    .coerceIn(MinFrameSkipIntervalMs, MaxFrameSkipIntervalMs),
                mode3InferenceIntervalMs = (preferences[HaramVeilPreferenceKeys.Mode3InferenceIntervalMs]
                    ?: HaramVeilPreferenceKeys.DefaultMode3InferenceIntervalMs)
                    .coerceIn(MinMode3InferenceIntervalMs, MaxMode3InferenceIntervalMs),
                lockdownDurationMs = (preferences[HaramVeilPreferenceKeys.LockdownDurationMs]
                    ?: HaramVeilPreferenceKeys.DefaultLockdownDurationMs)
                    .coerceIn(MinLockdownDurationMs, MaxLockdownDurationMs),
                statsRetentionDays = (preferences[HaramVeilPreferenceKeys.StatsRetentionDays]
                    ?: HaramVeilPreferenceKeys.DefaultStatsRetentionDays.toLong())
                    .coerceIn(MinStatsRetentionDays, MaxStatsRetentionDays)
                    .toInt(),
                topCapturePercent = (preferences[HaramVeilPreferenceKeys.TopCapturePercent]
                    ?: HaramVeilPreferenceKeys.DefaultTopCapturePercent.toLong())
                    .coerceIn(MinTopCapturePercent, MaxTopCapturePercent)
                    .toInt(),
                middleCapturePercent = (preferences[HaramVeilPreferenceKeys.MiddleCapturePercent]
                    ?: HaramVeilPreferenceKeys.DefaultMiddleCapturePercent.toLong())
                    .coerceIn(MinMiddleCapturePercent, MaxMiddleCapturePercent)
                    .toInt(),
                accessibilitySettingsPromptShown = preferences[HaramVeilPreferenceKeys.AccessibilitySettingsPromptShown]
                    ?: false,
                batteryOptimizationPromptShown = preferences[HaramVeilPreferenceKeys.BatteryOptimizationPromptShown]
                    ?: false,
                batteryOptimizationCompleted = preferences[HaramVeilPreferenceKeys.BatteryOptimizationCompleted]
                    ?: false,
            )
        }

    suspend fun readSettings(): ProtectionSettings = settingsFlow.first()

    suspend fun saveMonitoredPackages(packageNames: Set<String>) {
        context.haramVeilPreferencesDataStore.edit { preferences ->
            preferences[HaramVeilPreferenceKeys.SelectedPackages] = packageNames
            preferences[HaramVeilPreferenceKeys.AppSelectionSaved] = true
        }
    }

    suspend fun saveProtectionEnabled(enabled: Boolean) {
        context.haramVeilPreferencesDataStore.edit { preferences ->
            preferences[HaramVeilPreferenceKeys.ProtectionEnabled] = enabled
            if (enabled) {
                preferences.remove(HaramVeilPreferenceKeys.ProtectionPauseUntilMs)
            }
        }
        ProtectionBootstrapStore(context).setProtectionEnabled(enabled)
    }

    suspend fun pauseProtectionUntil(untilTimestampMs: Long) {
        context.haramVeilPreferencesDataStore.edit { preferences ->
            preferences[HaramVeilPreferenceKeys.ProtectionEnabled] = false
            preferences[HaramVeilPreferenceKeys.ProtectionPauseUntilMs] = untilTimestampMs
        }
        ProtectionBootstrapStore(context).setProtectionEnabled(false)
    }

    suspend fun resumeProtectionAfterPause() {
        context.haramVeilPreferencesDataStore.edit { preferences ->
            preferences[HaramVeilPreferenceKeys.ProtectionEnabled] = true
            preferences.remove(HaramVeilPreferenceKeys.ProtectionPauseUntilMs)
        }
        ProtectionBootstrapStore(context).setProtectionEnabled(true)
    }

    suspend fun saveKeywordBlocklist(entries: List<String>) {
        context.haramVeilPreferencesDataStore.edit { preferences ->
            val sanitizedEntries = entries
                .map(String::trim)
                .filter(String::isNotBlank)
                .toSet()
            preferences[HaramVeilPreferenceKeys.KeywordBlocklist] =
                if (sanitizedEntries.isEmpty()) {
                    DefaultKeywordBlocklist.entries.toSet()
                } else {
                    sanitizedEntries
                }
        }
    }

    suspend fun saveModeConfiguration(
        textEngine: TextRecognitionEngine,
        visualModel: VisualModelOption?,
        mode1Enabled: Boolean,
        mode2Enabled: Boolean,
        mode3Enabled: Boolean,
    ) {
        context.haramVeilPreferencesDataStore.edit { preferences ->
            preferences[HaramVeilPreferenceKeys.SelectedTextEngine] = textEngine.storageValue
            preferences[HaramVeilPreferenceKeys.Mode1Enabled] = mode1Enabled
            preferences[HaramVeilPreferenceKeys.Mode2Enabled] = mode2Enabled
            preferences[HaramVeilPreferenceKeys.Mode3Enabled] = mode3Enabled
            preferences[HaramVeilPreferenceKeys.ModeConfigurationSaved] = true
            if (visualModel != null) {
                preferences[HaramVeilPreferenceKeys.SelectedVisualModel] = visualModel.storageValue
            } else {
                preferences.remove(HaramVeilPreferenceKeys.SelectedVisualModel)
            }
        }
    }

    suspend fun saveMode3ForcedPackages(packageNames: Set<String>) {
        context.haramVeilPreferencesDataStore.edit { preferences ->
            if (packageNames.isEmpty()) {
                preferences.remove(HaramVeilPreferenceKeys.Mode3ForcedPackages)
            } else {
                preferences[HaramVeilPreferenceKeys.Mode3ForcedPackages] = packageNames
            }
        }
    }

    suspend fun markMode3ForcedSeeded() {
        context.haramVeilPreferencesDataStore.edit { preferences ->
            preferences[HaramVeilPreferenceKeys.Mode3ForcedSeeded] = true
        }
    }

    suspend fun saveStrictModeEnabled(enabled: Boolean) {
        context.haramVeilPreferencesDataStore.edit { preferences ->
            preferences[HaramVeilPreferenceKeys.StrictModeEnabled] = enabled
            if (enabled) {
                preferences[HaramVeilPreferenceKeys.FrameSkipIntervalMs] =
                    HaramVeilPreferenceKeys.StrictFrameSkipIntervalMs
                preferences[HaramVeilPreferenceKeys.Mode3InferenceIntervalMs] =
                    HaramVeilPreferenceKeys.StrictMode3InferenceIntervalMs
            }
        }
    }

    suspend fun saveFrameSkipIntervalMs(intervalMs: Long) {
        context.haramVeilPreferencesDataStore.edit { preferences ->
            preferences[HaramVeilPreferenceKeys.FrameSkipIntervalMs] =
                intervalMs.coerceIn(minimumValue = MinFrameSkipIntervalMs, maximumValue = MaxFrameSkipIntervalMs)
        }
    }

    suspend fun saveMode3InferenceIntervalMs(intervalMs: Long) {
        context.haramVeilPreferencesDataStore.edit { preferences ->
            preferences[HaramVeilPreferenceKeys.Mode3InferenceIntervalMs] =
                intervalMs.coerceIn(minimumValue = MinMode3InferenceIntervalMs, maximumValue = MaxMode3InferenceIntervalMs)
        }
    }

    suspend fun saveLockdownDurationMs(durationMs: Long) {
        context.haramVeilPreferencesDataStore.edit { preferences ->
            preferences[HaramVeilPreferenceKeys.LockdownDurationMs] =
                durationMs.coerceIn(minimumValue = MinLockdownDurationMs, maximumValue = MaxLockdownDurationMs)
        }
    }

    suspend fun saveStatsRetentionDays(retentionDays: Int) {
        context.haramVeilPreferencesDataStore.edit { preferences ->
            preferences[HaramVeilPreferenceKeys.StatsRetentionDays] =
                retentionDays.toLong().coerceIn(minimumValue = MinStatsRetentionDays, maximumValue = MaxStatsRetentionDays)
        }
    }

    suspend fun saveHaramClipConfiguration(
        topCapturePercent: Int,
        middleCapturePercent: Int,
    ) {
        context.haramVeilPreferencesDataStore.edit { preferences ->
            preferences[HaramVeilPreferenceKeys.TopCapturePercent] =
                topCapturePercent.coerceIn(minimumValue = MinTopCapturePercent, maximumValue = MaxTopCapturePercent).toLong()
            preferences[HaramVeilPreferenceKeys.MiddleCapturePercent] =
                middleCapturePercent.coerceIn(minimumValue = MinMiddleCapturePercent, maximumValue = MaxMiddleCapturePercent).toLong()
        }
    }

    suspend fun saveDomainBlocklist(entries: List<String>) {
        context.haramVeilPreferencesDataStore.edit { preferences ->
            val sanitizedEntries = entries
                .map(String::trim)
                .filter(String::isNotBlank)
                .toSet()
            preferences[HaramVeilPreferenceKeys.DomainBlocklist] =
                if (sanitizedEntries.isEmpty()) {
                    DefaultDomainBlocklist.entries.toSet()
                } else {
                    sanitizedEntries
                }
        }
    }

    suspend fun markAccessibilitySettingsPromptShown() {
        context.haramVeilPreferencesDataStore.edit { preferences ->
            preferences[HaramVeilPreferenceKeys.AccessibilitySettingsPromptShown] = true
        }
    }

    suspend fun markBatteryOptimizationPromptShown() {
        context.haramVeilPreferencesDataStore.edit { preferences ->
            preferences[HaramVeilPreferenceKeys.BatteryOptimizationPromptShown] = true
        }
    }

    suspend fun markBatteryOptimizationCompleted(completed: Boolean = true) {
        context.haramVeilPreferencesDataStore.edit { preferences ->
            preferences[HaramVeilPreferenceKeys.BatteryOptimizationCompleted] = completed
        }
    }

    // Bounds shared by the read and the save paths so a stored value can never drift out of range.
    private companion object {
        const val MinFrameSkipIntervalMs = 250L
        const val MaxFrameSkipIntervalMs = 2_000L
        const val MinMode3InferenceIntervalMs = 1_000L
        const val MaxMode3InferenceIntervalMs = 2_000L
        const val MinLockdownDurationMs = 5 * 60 * 1_000L
        const val MaxLockdownDurationMs = 24 * 60 * 60 * 1_000L
        const val MinStatsRetentionDays = 30L
        const val MaxStatsRetentionDays = 365L
        const val MinTopCapturePercent = 10L
        const val MaxTopCapturePercent = 45L
        const val MinMiddleCapturePercent = 20L
        const val MaxMiddleCapturePercent = 50L
    }
}

object DefaultKeywordBlocklist {
    val entries: List<String> = listOf(
        "nsfw",
        "porn",
        "sex",
        "sexo",
        "seks",
        "onlyfans",
        "xxx",
        "adult",
        "adulto",
        "18+",
        "18plus",
        "x-rated",
        "hentai",
        "dating",
        "hookup",
        "escort",
        "nude",
        "desnudo",
        "explicit",
        "erotico",
        "erotica",
        "pornografia",
        "milf",
        "teen",
        " fetish",
        "blowjob",
        "handjob",
        "threesome",
        "orgy",
        "anal",
        "voyeur",
        "strip",
        "peep",
        "cam girl",
        "adult video",
        "explicit content",
        "xxx video",
        "hot girl",
        "hot video",
        "leaked video",
        "private video",
        "stealth gram",
        "18+ video",
        "uncensored",
        "jav uncensored",
        "filegator",
        "linkvertise",
        "skippyfile",
        "shaved",
        "bikini",
        "lingerie",
        "underwear",
        "bra",
        "panties",
        "boobs",
        "ass",
        "cum",
        "orgasm",
        "vibrator",
        "dildo",
        "latex",
        "bondage",
        "bdsm",
        "fisting",
        "rimming",
        "tribbing",
        "strapon",
        "deep throat",
        "face sit",
        "foot job",
        "(?i)cam\\s?girl",
        "(?i)adult\\s?video",
        "(?i)explicit\\s?content",
        "(?i)xxx\\s?video",
    )
}

object DefaultDomainBlocklist {
    val entries: List<String> = listOf(
        "pornhub.com",
        "xvideos.com",
        "xnxx.com",
        "xhamster.com",
        "redtube.com",
        "youporn.com",
        "brazzers.com",
        "chaturbate.com",
        "stripchat.com",
        "onlyfans.com",
        "fansly.com",
        "sex.com",
        "adultfriendfinder.com",
        "xblues.com",
        "spankbang.com",
        "tnafl.com",
        "naughtymatch.com",
        "dating.com",
        "amateurtube.com",
        "beegt.com",
        "hentaihaven.com",
        "rule34.xxx",
        "xfreeones.com",
    )
}
