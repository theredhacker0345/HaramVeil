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

package com.haramveil.detection.mode2

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.os.Process
import com.haramveil.data.local.ProtectionPreferencesRepository
import com.haramveil.data.local.StatsRepository
import com.haramveil.data.models.ProtectionSettings
import com.haramveil.utils.DetectionBus
import com.haramveil.utils.DetectionEvent
import com.haramveil.utils.DetectionTriggerMode
import com.haramveil.utils.DispatcherProvider
import com.haramveil.utils.Mode1WakeRequest
import com.haramveil.utils.ScreenCaptureManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class Mode2Processor(
    context: Context,
    private val accessibilityServiceProvider: () -> AccessibilityService?,
    private val protectionPreferencesRepository: ProtectionPreferencesRepository,
    private val settingsProvider: () -> ProtectionSettings,
    private val dispatcherProvider: DispatcherProvider = DispatcherProvider(),
) {
    private val processorScope = CoroutineScope(SupervisorJob() + dispatcherProvider.default)
    private val screenCaptureManager = ScreenCaptureManager(dispatcherProvider)
    private val statsRepository = StatsRepository.getInstance(context)
    private val ocrEngineSelector = OcrEngineSelector(
        context = context,
        protectionPreferencesRepository = protectionPreferencesRepository,
        dispatcherProvider = dispatcherProvider,
    )
    private var processorJob: Job? = null

    fun start() {
        if (processorJob?.isActive == true) {
            return
        }

        processorJob = processorScope.launch(dispatcherProvider.default) {
            DetectionBus.events
                .filterIsInstance<DetectionEvent.Mode1Triggered>()
                .collectLatest { event ->
                    runCatching {
                        processTrigger(event.trigger)
                    }.onFailure { error ->
                        com.haramveil.utils.DebugLog.w(applicationContext, "HaramVeilMode2") {
                            "Mode 2 processing failed for ${event.trigger.scanResult.packageName}: ${error.message}"
                        }
                    }
                }
        }
    }

    fun stop() {
        processorJob?.cancel()
        processorJob = null
    }

    fun destroy() {
        stop()
        ocrEngineSelector.close()
        processorScope.cancel()
    }

    private suspend fun processTrigger(trigger: Mode1WakeRequest) {
        withContext(dispatcherProvider.default) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)

            val settings = settingsProvider()
            if (!settings.mode2Enabled || !trigger.wakeMode2) {
                return@withContext
            }

            val accessibilityService = accessibilityServiceProvider() ?: run {
                DetectionBus.publishMode2Clear(
                    packageName = trigger.scanResult.packageName,
                    details = "Accessibility service instance was unavailable for OCR capture.",
                )
                return@withContext
            }

            val captureBitmap = screenCaptureManager.captureHaramClip(accessibilityService, settings) ?: run {
                DetectionBus.publishMode2Clear(
                    packageName = trigger.scanResult.packageName,
                    details = "Screenshot capture is unavailable on this device or Android version.",
                )
                return@withContext
            }

            try {
                val ocrEngine = ocrEngineSelector.select(settings.selectedTextEngine)
                val ocrResult = ocrEngine.extractText(captureBitmap)
                val matchedDomain = findMatchedDomain(
                    extractedText = ocrResult.text,
                    domainBlocklist = settings.domainBlocklist,
                )
                val matchedKeyword = findMatchedKeyword(
                    extractedText = ocrResult.text,
                    keywordBlocklist = settings.keywordBlocklist,
                )
                val matchedToken = matchedDomain ?: matchedKeyword

                if (matchedToken != null) {
                    val matchDetails =
                        "OCR matched \"$matchedToken\" using ${ocrEngine.label()} in ${ocrResult.processingTimeMs}ms."
                    statsRepository.logBlock(
                        packageName = trigger.scanResult.packageName,
                        mode = 2,
                        detail = matchedToken,
                        lockdownMs = settings.lockdownDurationMs,
                    )
                    DetectionBus.publishMode2Triggered(
                        packageName = trigger.scanResult.packageName,
                        matchDetails = matchDetails,
                    )
                    DetectionBus.publishVeilRequested(
                        packageName = trigger.scanResult.packageName,
                        triggerMode = DetectionTriggerMode.MODE_2,
                        matchDetails = matchDetails,
                    )
                } else {
                    DetectionBus.publishMode2Clear(
                        packageName = trigger.scanResult.packageName,
                        details = if (ocrResult.text.isBlank()) {
                            "OCR completed without readable text."
                        } else {
                            "OCR found no blocked keywords."
                        },
                    )
                }
            } finally {
                recycleBitmap(captureBitmap)
            }
        }
    }

    private fun findMatchedKeyword(
        extractedText: String,
        keywordBlocklist: List<String>,
    ): String? {
        if (extractedText.isBlank() || keywordBlocklist.isEmpty()) {
            return null
        }

        return keywordBlocklist.firstOrNull { entry ->
            val trimmedEntry = entry.trim()
            if (trimmedEntry.isEmpty()) {
                return@firstOrNull false
            }

            if (looksLikeRegex(trimmedEntry)) {
                runCatching {
                    Regex(trimmedEntry, RegexOption.IGNORE_CASE).containsMatchIn(extractedText)
                }.getOrDefault(false)
            } else {
                extractedText.contains(other = trimmedEntry, ignoreCase = true)
            }
        }
    }

    private fun looksLikeRegex(entry: String): Boolean =
        entry.any { character -> character in RegexMetaCharacters }

    private fun looksLikeDomainRegex(entry: String): Boolean =
        entry.any { character -> character in DomainRegexMetaCharacters }

    private fun findMatchedDomain(
        extractedText: String,
        domainBlocklist: List<String>,
    ): String? {
        if (extractedText.isBlank() || domainBlocklist.isEmpty()) {
            return null
        }

        val candidates = buildList {
            add(extractedText)
            DomainRegex.findAll(extractedText.lowercase())
                .map { normalizeDomainToken(it.value) }
                .filter(String::isNotBlank)
                .forEach { add(it) }
        }

        return domainBlocklist.firstNotNullOfOrNull { entry ->
            val trimmedEntry = entry.trim()
            if (trimmedEntry.isEmpty()) {
                return@firstNotNullOfOrNull null
            }
            if (looksLikeDomainRegex(trimmedEntry)) {
                val regex = runCatching { Regex(trimmedEntry, RegexOption.IGNORE_CASE) }.getOrNull()
                    ?: return@firstNotNullOfOrNull null
                candidates.firstOrNull { candidate ->
                    regex.containsMatchIn(candidate)
                }?.let { trimmedEntry }
            } else {
                val normalizedEntry = normalizeDomainToken(trimmedEntry)
                if (normalizedEntry.isEmpty()) {
                    return@firstNotNullOfOrNull null
                }
                candidates.firstOrNull { candidate ->
                    val normalizedCandidate = normalizeDomainToken(candidate)
                    normalizedCandidate.contains(normalizedEntry) ||
                        candidate.contains(other = normalizedEntry, ignoreCase = true)
                }?.let { normalizeDomainToken(it) }
            }
        }
    }

    private fun normalizeDomainToken(value: String): String {
        val trimmed = value.trim().lowercase()
        val withoutScheme = trimmed
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
        val domain = withoutScheme.takeWhile { char ->
            char.isLetterOrDigit() || char == '.' || char == '-'
        }
        return domain.trim('.')
    }

    private fun recycleBitmap(bitmap: Bitmap) {
        if (!bitmap.isRecycled) {
            bitmap.recycle()
        }
    }

    private fun OcrEngine.label(): String =
        when (this) {
            is MLKitOcrEngine -> "ML Kit"
            is FossOcrEngine -> "FOSS RapidOCR"
            else -> "OCR"
        }

    private companion object {
        val RegexMetaCharacters = setOf(
            '\\',
            '^',
            '$',
            '.',
            '|',
            '?',
            '*',
            '+',
            '(',
            ')',
            '[',
            ']',
            '{',
            '}',
        )

        val DomainRegexMetaCharacters = RegexMetaCharacters - '.'
        val DomainRegex = Regex("([a-z0-9-]+\\.)+[a-z]{2,}")
    }
}
