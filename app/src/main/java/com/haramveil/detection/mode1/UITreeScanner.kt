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

package com.haramveil.detection.mode1

import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
}

enum class MatchSource {
    KEYWORD,
    DOMAIN,
}

data class ScanResult(
    val triggered: Boolean,
    val matchedKeyword: String?,
    val matchSource: MatchSource? = null,
    val packageName: String,
    val riskLevel: RiskLevel,
)

class UITreeScanner {

    fun scan(
        rootNode: AccessibilityNodeInfo?,
        packageName: String,
        keywordBlocklist: List<String>,
        domainBlocklist: List<String>,
        isMonitoredApp: Boolean,
        isBrowser: Boolean,
        isHighRisk: Boolean,
    ): ScanResult {
        if (rootNode == null) {
            return ScanResult(
                triggered = false,
                matchedKeyword = null,
                matchSource = null,
                packageName = packageName,
                riskLevel = RiskLevel.LOW,
            )
        }

        val extractedSignals = collectVisibleSignals(rootNode, isBrowser || isHighRisk)
        val matchedDomain = if (isBrowser) {
            findMatchedDomain(
                extractedSignals = extractedSignals,
                domainBlocklist = domainBlocklist,
            )
        } else {
            null
        }
        val matchedKeyword = findMatchedKeyword(
            extractedSignals = extractedSignals,
            keywordBlocklist = keywordBlocklist,
        )
        val matchedToken = matchedDomain ?: matchedKeyword
        val matchSource = when {
            matchedDomain != null -> MatchSource.DOMAIN
            matchedKeyword != null -> MatchSource.KEYWORD
            else -> null
        }
        val riskLevel = when {
            matchedToken != null -> RiskLevel.HIGH
            isHighRisk || isMonitoredApp -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
        return ScanResult(
            triggered = riskLevel != RiskLevel.LOW,
            matchedKeyword = matchedToken,
            matchSource = matchSource,
            packageName = packageName,
            riskLevel = riskLevel,
        )
    }

    fun computeNodeHash(rootNode: AccessibilityNodeInfo?, isBrowser: Boolean, isHighRisk: Boolean): Int {
        if (rootNode == null) {
            return 0
        }

        val packageName = rootNode.packageName?.toString().orEmpty()
        val localIsBrowser = if (packageName.isNotEmpty()) isBrowserPackage(packageName) else isBrowser
        val localIsHighRisk = if (packageName.isNotEmpty()) {
            isHighRiskPackage(packageName)
        } else {
            // If both are unavailable, treat as high-risk per security requirement
            isHighRisk || !isBrowser
        }

        val effectiveIsBrowser = isBrowser || localIsBrowser
        val effectiveIsHighRisk = isHighRisk || localIsHighRisk

        val extractedSignals = collectVisibleSignals(rootNode, effectiveIsBrowser || effectiveIsHighRisk)
        return buildString {
            append(packageName)
            append("|")
            append(extractedSignals.visibleTexts.joinToString(separator = "|"))
            append("|")
            append(extractedSignals.resourceIds.joinToString(separator = "|"))
            append("|")
            append(extractedSignals.contentDescriptions.joinToString(separator = "|"))
        }.hashCode()
    }

    fun isHighRiskPackage(packageName: String): Boolean {
        val normalizedPackage = packageName.lowercase(Locale.ROOT)
        return normalizedPackage in HighRiskPackages ||
            HighRiskKeywords.any { keyword -> normalizedPackage.contains(keyword) }
    }

    fun isBrowserPackage(packageName: String): Boolean {
        val normalizedPackage = packageName.lowercase(Locale.ROOT)
        return normalizedPackage in BrowserPackages ||
            BrowserKeywords.any { keyword -> normalizedPackage.contains(keyword) }
    }

    private fun collectVisibleSignals(
        rootNode: AccessibilityNodeInfo,
        isBrowserOrHighRisk: Boolean = false,
    ): ExtractedUiSignals {
        val visibleTexts = linkedSetOf<String>()
        val resourceIds = linkedSetOf<String>()
        val contentDescriptions = linkedSetOf<String>()
        val traversalStack = ArrayDeque<AccessibilityNodeInfo>()
        traversalStack.addLast(rootNode)

        while (traversalStack.isNotEmpty()) {
            val currentNode = traversalStack.removeLast()
            if (currentNode.isVisibleToUser || isBrowserOrHighRisk) {
                currentNode.text
                    ?.toString()
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?.let(visibleTexts::add)

                currentNode.viewIdResourceName
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?.let(resourceIds::add)

                currentNode.contentDescription
                    ?.toString()
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?.let(contentDescriptions::add)
            }

            for (childIndex in 0 until currentNode.childCount) {
                currentNode.getChild(childIndex)?.let { childNode ->
                    traversalStack.addLast(childNode)
                }
            }
        }

        return ExtractedUiSignals(
            visibleTexts = visibleTexts.toList(),
            resourceIds = resourceIds.toList(),
            contentDescriptions = contentDescriptions.toList(),
        )
    }

    private fun findMatchedKeyword(
        extractedSignals: ExtractedUiSignals,
        keywordBlocklist: List<String>,
    ): String? {
        if (keywordBlocklist.isEmpty()) {
            return null
        }

        val searchableSignals = buildList {
            addAll(extractedSignals.visibleTexts)
            addAll(extractedSignals.contentDescriptions)
            addAll(extractedSignals.resourceIds)
        }

        return keywordBlocklist.firstOrNull { entry ->
            val trimmedEntry = entry.trim()
            if (trimmedEntry.isEmpty()) {
                return@firstOrNull false
            }

            searchableSignals.any { signal ->
                if (looksLikeRegex(trimmedEntry)) {
                    runCatching {
                        Regex(trimmedEntry, RegexOption.IGNORE_CASE).containsMatchIn(signal)
                    }.getOrDefault(false)
                } else {
                    signal.contains(other = trimmedEntry, ignoreCase = true)
                }
            }
        }
    }

    private fun findMatchedDomain(
        extractedSignals: ExtractedUiSignals,
        domainBlocklist: List<String>,
    ): String? {
        if (domainBlocklist.isEmpty()) {
            return null
        }

        val rawSignals = buildList {
            addAll(extractedSignals.visibleTexts)
            addAll(extractedSignals.contentDescriptions)
        }
        val candidateDomains = buildList {
            rawSignals.forEach { signal ->
                val normalizedSignal = signal.trim()
                if (normalizedSignal.isNotEmpty()) {
                    add(normalizedSignal)
                }
                extractDomainsFromText(normalizedSignal).forEach { domain ->
                    add(domain)
                }
            }
        }

        return domainBlocklist.firstNotNullOfOrNull { entry ->
            val trimmedEntry = entry.trim()
            if (trimmedEntry.isEmpty()) {
                return@firstNotNullOfOrNull null
            }
            if (looksLikeDomainRegex(trimmedEntry)) {
                val regex = runCatching { Regex(trimmedEntry, RegexOption.IGNORE_CASE) }.getOrNull()
                    ?: return@firstNotNullOfOrNull null
                candidateDomains.firstOrNull { candidate ->
                    regex.containsMatchIn(candidate)
                }?.let { trimmedEntry }
            } else {
                val normalizedEntry = normalizeDomainToken(trimmedEntry)
                if (normalizedEntry.isEmpty()) {
                    return@firstNotNullOfOrNull null
                }
                candidateDomains.firstOrNull { candidate ->
                    val normalizedCandidate = normalizeDomainToken(candidate)
                    normalizedCandidate.contains(normalizedEntry) ||
                        candidate.contains(other = normalizedEntry, ignoreCase = true)
                }?.let { normalizeDomainToken(it) }
            }
        }
    }

    private fun looksLikeRegex(entry: String): Boolean =
        entry.any { character -> character in RegexMetaCharacters }

    private fun looksLikeDomainRegex(entry: String): Boolean =
        entry.any { character -> character in DomainRegexMetaCharacters }

    private fun extractDomainsFromText(text: String): List<String> =
        DomainRegex.findAll(text.lowercase(Locale.ROOT))
            .map { normalizeDomainToken(it.value) }
            .filter(String::isNotBlank)
            .toList()

    private fun normalizeDomainToken(value: String): String {
        val trimmed = value.trim().lowercase(Locale.ROOT)
        val withoutScheme = trimmed
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
        val domain = withoutScheme.takeWhile { char ->
            char.isLetterOrDigit() || char == '.' || char == '-'
        }
        return domain.trim('.')
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

        val HighRiskPackages: Set<String> = setOf(
            "com.instagram.android",
            "com.zhiliaoapp.musically",
            "com.android.chrome",
            "com.google.android.apps.chrome",
            "org.mozilla.firefox",
            "com.sec.android.app.sbrowser",
            "com.snapchat.android",
            "com.twitter.android",
            "com.xcorp.android",
            "com.google.android.youtube",
            "com.reddit.frontpage",
            "com.opera.browser",
            "com.brave.browser",
            "com.duckduckgo.mobile.android",
            "org.telegram.messenger",
            "com.whatsapp",
            "com.microsoft.emmx",
        )

        val HighRiskKeywords: List<String> = listOf(
            "browser",
            "video",
            "instagram",
            "tiktok",
            "twitter",
            "xcorp",
            "youtube",
            "reddit",
            "opera",
            "brave",
            "duckduckgo",
            "telegram",
            "whatsapp",
            "chrome",
            "firefox",
            "samsunginternet",
            "snapchat",
            "edge",
        )

        val BrowserPackages: Set<String> = setOf(
            "com.android.chrome",
            "com.google.android.apps.chrome",
            "org.mozilla.firefox",
            "com.sec.android.app.sbrowser",
            "com.opera.browser",
            "com.brave.browser",
            "com.duckduckgo.mobile.android",
            "com.microsoft.emmx",
        )

        val BrowserKeywords: List<String> = listOf(
            "browser",
            "chrome",
            "firefox",
            "samsunginternet",
            "opera",
            "brave",
            "duckduckgo",
            "edge",
        )

        val DomainRegex = Regex("([a-z0-9-]+\\.)+[a-z]{2,}")
    }
}

private data class ExtractedUiSignals(
    val visibleTexts: List<String>,
    val resourceIds: List<String>,
    val contentDescriptions: List<String>,
)
