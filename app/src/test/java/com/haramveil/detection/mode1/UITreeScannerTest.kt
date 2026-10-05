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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

class UITreeScannerTest {

    private val scanner = UITreeScanner()

    @Test
    fun scan_returnsHighRisk_whenVisibleTextContainsBlockedKeyword() {
        val rootNode = mock(AccessibilityNodeInfo::class.java)
        val childNode = mock(AccessibilityNodeInfo::class.java)

        `when`(rootNode.isVisibleToUser).thenReturn(true)
        `when`(rootNode.childCount).thenReturn(1)
        `when`(rootNode.getChild(0)).thenReturn(childNode)
        `when`(rootNode.text).thenReturn(null)
        `when`(rootNode.contentDescription).thenReturn(null)
        `when`(rootNode.viewIdResourceName).thenReturn("com.instagram.android:id/root")

        `when`(childNode.isVisibleToUser).thenReturn(true)
        `when`(childNode.childCount).thenReturn(0)
        `when`(childNode.text).thenReturn("Suggested NSFW content")
        `when`(childNode.contentDescription).thenReturn(null)
        `when`(childNode.viewIdResourceName).thenReturn("com.instagram.android:id/caption")

        val result = scanner.scan(
            rootNode = rootNode,
            packageName = "com.instagram.android",
            keywordBlocklist = listOf("nsfw"),
            domainBlocklist = emptyList(),
            isMonitoredApp = true,
            isBrowser = false,
            isHighRisk = true,
        )

        assertTrue(result.triggered)
        assertEquals("nsfw", result.matchedKeyword)
        assertEquals("com.instagram.android", result.packageName)
        assertEquals(RiskLevel.HIGH, result.riskLevel)
    }

    @Test
    fun scan_returnsHighRisk_whenVisibleTextContainsBlockedDomain() {
        val rootNode = mock(AccessibilityNodeInfo::class.java)
        val childNode = mock(AccessibilityNodeInfo::class.java)

        `when`(rootNode.isVisibleToUser).thenReturn(true)
        `when`(rootNode.packageName).thenReturn("com.android.chrome")
        `when`(rootNode.childCount).thenReturn(1)
        `when`(rootNode.getChild(0)).thenReturn(childNode)
        `when`(rootNode.text).thenReturn(null)
        `when`(rootNode.contentDescription).thenReturn(null)

        `when`(childNode.isVisibleToUser).thenReturn(true)
        `when`(childNode.childCount).thenReturn(0)
        `when`(childNode.text).thenReturn("https://blocked-example.com/some-page")
        `when`(childNode.contentDescription).thenReturn(null)

        val result = scanner.scan(
            rootNode = rootNode,
            packageName = "com.android.chrome",
            keywordBlocklist = emptyList(),
            domainBlocklist = listOf("blocked-example.com"),
            isMonitoredApp = true,
            isBrowser = true,
            isHighRisk = true,
        )

        assertTrue(result.triggered)
        assertEquals("blocked-example.com", result.matchedKeyword)
        assertEquals(MatchSource.DOMAIN, result.matchSource)
        assertEquals("com.android.chrome", result.packageName)
        assertEquals(RiskLevel.HIGH, result.riskLevel)
    }

    @Test
    fun computeNodeHash_usesLocalPackageName() {
        val rootNode = mock(AccessibilityNodeInfo::class.java)
        `when`(rootNode.packageName).thenReturn("com.android.chrome")
        `when`(rootNode.isVisibleToUser).thenReturn(false) // Hidden node
        `when`(rootNode.text).thenReturn("Browser Text")

        // Even if passed as false, it should pick up that it's a browser from packageName
        val hash = scanner.computeNodeHash(rootNode, isBrowser = false, isHighRisk = false)

        // If it was NOT treated as browser, it would skip "Browser Text" because isVisibleToUser is false.
        assertTrue(hash != 0)
    }

    @Test
    fun computeNodeHash_defaultsToHighRisk_whenPackageNameIsEmpty() {
        val rootNode = mock(AccessibilityNodeInfo::class.java)
        `when`(rootNode.packageName).thenReturn("")
        `when`(rootNode.isVisibleToUser).thenReturn(false) // Hidden node
        `when`(rootNode.text).thenReturn("Hidden High Risk Text")

        // Package name empty, isBrowser=false, isHighRisk=false -> should treat as high risk
        val hash = scanner.computeNodeHash(rootNode, isBrowser = false, isHighRisk = false)
        assertTrue(hash != 0)
    }

    @Test
    fun scan_recyclesChildNodes_withoutRecyclingRoot() {
        val rootNode = mock(AccessibilityNodeInfo::class.java)
        val childNode = mock(AccessibilityNodeInfo::class.java)
        val grandChildNode = mock(AccessibilityNodeInfo::class.java)

        `when`(rootNode.isVisibleToUser).thenReturn(true)
        `when`(rootNode.childCount).thenReturn(1)
        `when`(rootNode.getChild(0)).thenReturn(childNode)
        `when`(rootNode.text).thenReturn(null)
        `when`(rootNode.contentDescription).thenReturn(null)
        `when`(rootNode.viewIdResourceName).thenReturn(null)

        `when`(childNode.isVisibleToUser).thenReturn(true)
        `when`(childNode.childCount).thenReturn(1)
        `when`(childNode.getChild(0)).thenReturn(grandChildNode)
        `when`(childNode.text).thenReturn("harmless feed")
        `when`(childNode.contentDescription).thenReturn(null)
        `when`(childNode.viewIdResourceName).thenReturn(null)

        `when`(grandChildNode.isVisibleToUser).thenReturn(true)
        `when`(grandChildNode.childCount).thenReturn(0)
        `when`(grandChildNode.text).thenReturn("more harmless text")
        `when`(grandChildNode.contentDescription).thenReturn(null)
        `when`(grandChildNode.viewIdResourceName).thenReturn(null)

        scanner.scan(
            rootNode = rootNode,
            packageName = "com.example.notes",
            keywordBlocklist = emptyList(),
            domainBlocklist = emptyList(),
            isMonitoredApp = false,
            isBrowser = false,
            isHighRisk = false,
        )

        verify(childNode).recycle()
        verify(grandChildNode).recycle()
        verify(rootNode, never()).recycle()
    }

    @Test
    fun computeNodeHash_recyclesChildNodes_withoutRecyclingRoot() {
        val rootNode = mock(AccessibilityNodeInfo::class.java)
        val childNode = mock(AccessibilityNodeInfo::class.java)

        `when`(rootNode.packageName).thenReturn("com.example.notes")
        `when`(rootNode.isVisibleToUser).thenReturn(true)
        `when`(rootNode.childCount).thenReturn(1)
        `when`(rootNode.getChild(0)).thenReturn(childNode)
        `when`(rootNode.text).thenReturn(null)
        `when`(rootNode.contentDescription).thenReturn(null)
        `when`(rootNode.viewIdResourceName).thenReturn(null)

        `when`(childNode.isVisibleToUser).thenReturn(true)
        `when`(childNode.childCount).thenReturn(0)
        `when`(childNode.text).thenReturn("harmless feed")
        `when`(childNode.contentDescription).thenReturn(null)
        `when`(childNode.viewIdResourceName).thenReturn(null)

        scanner.computeNodeHash(rootNode, isBrowser = false, isHighRisk = false)

        verify(childNode).recycle()
        verify(rootNode, never()).recycle()
    }

    @Test
    fun isHighRiskPackage_ignoresKeywordSubstringFalsePositives() {
        assertFalse(scanner.isHighRiskPackage("com.example.videoplayer"))
        assertFalse(scanner.isHighRiskPackage("com.example.datingapp"))
        assertFalse(scanner.isHighRiskPackage("com.example.matchbox"))
    }

    @Test
    fun isBrowserPackage_ignoresKeywordSubstringFalsePositives() {
        assertFalse(scanner.isBrowserPackage("com.example.edgedriver"))
    }

    @Test
    fun isHighRiskPackage_matchesKnownPackagesAndSegments() {
        assertTrue(scanner.isHighRiskPackage("com.grindrapp"))
        assertTrue(scanner.isHighRiskPackage("com.x.android"))
        assertTrue(scanner.isHighRiskPackage("com.example.dating"))
    }

    @Test
    fun isBrowserPackage_matchesKnownPackagesAndSegments() {
        assertTrue(scanner.isBrowserPackage("com.android.chrome"))
        assertTrue(scanner.isBrowserPackage("com.example.chrome2"))
    }
}
