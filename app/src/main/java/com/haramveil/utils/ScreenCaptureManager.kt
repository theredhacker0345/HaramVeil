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

package com.haramveil.utils
import com.haramveil.utils.DebugLog

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.SystemClock
import android.view.Display
import androidx.core.content.ContextCompat
import com.haramveil.data.models.ProtectionSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

class ScreenCaptureManager(
    private val dispatcherProvider: DispatcherProvider = DispatcherProvider(),
) {

    /**
     * Full-screen software capture, meant for Mode 3 visual inference. The AI model was
     * trained on complete scenes: cropping bands out of the screen and stacking them into
     * a synthetic strip distorts the layout the detector expects and hides everything
     * outside the bands.
     */
    suspend fun captureFullScreen(
        accessibilityService: AccessibilityService,
    ): Bitmap? = captureInternal(accessibilityService) { hardwareBuffer, colorSpace ->
        wrapAsSoftwareBitmap(hardwareBuffer, colorSpace)
    }

    /**
     * Banded capture (top + middle bands stacked), meant for Mode 2 OCR where the
     * address bar and main text column live. Text in the lower third is intentionally
     * out of scope to keep the strip small.
     */
    suspend fun captureHaramClip(
        accessibilityService: AccessibilityService,
        settings: ProtectionSettings,
    ): Bitmap? = captureInternal(accessibilityService) { hardwareBuffer, colorSpace ->
        val sourceBitmap = wrapAsSoftwareBitmap(hardwareBuffer, colorSpace) ?: return@captureInternal null
        try {
            combineCaptureZones(
                sourceBitmap = sourceBitmap,
                topCapturePercent = settings.topCapturePercent,
                middleCapturePercent = settings.middleCapturePercent,
            )
        } finally {
            runCatching { sourceBitmap.recycle() }
        }
    }

    /**
     * Screenshot capture serialized process-wide: `AccessibilityService.takeScreenshot`
     * allows only one in-flight request per display, and Mode 2 and Mode 3 each keep
     * their own ScreenCaptureManager, so contention must be resolved across instances.
     */
    private suspend fun <T> captureInternal(
        accessibilityService: AccessibilityService,
        consume: (HardwareBuffer, ColorSpace?) -> T?,
    ): T? = withContext(dispatcherProvider.default) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return@withContext null
        }

            CaptureMutex.withLock {
                var lastAttemptFailed = false
                repeat(CaptureAttempts) { attempt ->
                    // The platform rate-limits takeScreenshot to one request per second
                    // (ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT). The mutex serialises
                    // the callers but not the spacing, so before each request wait out the
                    // remainder of the window since the last attempt; the retry delay then
                    // only covers the failure case.
                    val sinceLastAttemptMs = SystemClock.elapsedRealtime() - lastCaptureAttemptAtMs
                    if (sinceLastAttemptMs < ScreenshotMinIntervalMs) {
                        delay(ScreenshotMinIntervalMs - sinceLastAttemptMs)
                    }
                    lastCaptureAttemptAtMs = SystemClock.elapsedRealtime()
                    val screenshotResult = withTimeoutOrNull(ScreenshotTimeoutMs) {
                        awaitScreenshot(accessibilityService)
                    }
                    if (screenshotResult == null) {
                        lastAttemptFailed = true
                        if (attempt < CaptureAttempts - 1) {
                            delay(CaptureRetryDelayMs)
                        }
                        return@repeat
                    }
                    val hardwareBuffer: HardwareBuffer = screenshotResult.hardwareBuffer
                    try {
                        val consumed = consume(hardwareBuffer, screenshotResult.colorSpace)
                        if (consumed != null) {
                            return@withLock consumed
                        }
                        lastAttemptFailed = true
                    } finally {
                        runCatching { hardwareBuffer.close() }
                    }
                    // Null from consume() means the buffer was unusable; retry.
                }
                if (lastAttemptFailed) {
                    DebugLog.w(accessibilityService, "HaramVeilCapture") {
                        "Screenshot capture unavailable after $CaptureAttempts attempts."
                    }
                }
                null
            }
        }

    fun prepareForVisualModel(
        bitmap: Bitmap,
        inputSize: Int,
    ): Bitmap {
        val maxLongEdge = max(inputSize, inputSize * 2)
        val currentLongEdge = max(bitmap.width, bitmap.height)
        if (currentLongEdge <= maxLongEdge) {
            return bitmap
        }

        val scale = maxLongEdge.toFloat() / currentLongEdge.toFloat()
        val targetWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        if (scaledBitmap.config == Bitmap.Config.RGB_565) {
            return scaledBitmap
        }

        return scaledBitmap.copy(Bitmap.Config.RGB_565, false).also { convertedBitmap ->
            if (convertedBitmap !== scaledBitmap && !scaledBitmap.isRecycled) {
                scaledBitmap.recycle()
            }
        }
    }

    /**
     * Wraps the screenshot HardwareBuffer and immediately deep-copies it into a software
     * ARGB_8888 bitmap. The wrapped bitmap is HARDWARE-backed: drawing it onto a software
     * canvas throws ("Software rendering doesn't support hardware bitmaps"), so every
     * consumer — canvas cropping, getPixels, createScaledBitmap — needs the copy first.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun wrapAsSoftwareBitmap(
        hardwareBuffer: HardwareBuffer,
        colorSpace: ColorSpace?,
    ): Bitmap? {
        val wrappedBitmap = Bitmap.wrapHardwareBuffer(
            hardwareBuffer,
            colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB),
        ) ?: return null

        return try {
            wrappedBitmap.copy(Bitmap.Config.ARGB_8888, false)
        } finally {
            runCatching { wrappedBitmap.recycle() }
        }
    }

    @Suppress("DEPRECATION")
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private suspend fun awaitScreenshot(
        accessibilityService: AccessibilityService,
    ): AccessibilityService.ScreenshotResult? =
        suspendCancellableCoroutine { continuation ->
            accessibilityService.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                ContextCompat.getMainExecutor(accessibilityService),
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        if (continuation.isActive) {
                            continuation.resume(screenshot)
                        } else {
                            // The awaiting coroutine already timed out or was cancelled.
                            runCatching { screenshot.hardwareBuffer?.close() }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        if (continuation.isActive) {
                            DebugLog.w(accessibilityService, "HaramVeilCapture") { "takeScreenshot failed with errorCode=$errorCode" }
                            continuation.resume(null)
                        }
                    }
                },
            )
        }

    private fun combineCaptureZones(
        sourceBitmap: Bitmap,
        topCapturePercent: Int,
        middleCapturePercent: Int,
    ): Bitmap? {
        if (sourceBitmap.width <= 0 || sourceBitmap.height <= 0) {
            return null
        }

        val width = sourceBitmap.width
        val height = sourceBitmap.height
        val clampedTopPercent = topCapturePercent.coerceIn(minimumValue = 10, maximumValue = 45)
        val clampedMiddlePercent = middleCapturePercent.coerceIn(minimumValue = 20, maximumValue = 50)
        val topHeight = (height * (clampedTopPercent / 100f)).roundToInt().coerceAtLeast(1)
        val middleStart = (height * 0.35f).roundToInt().coerceIn(minimumValue = 0, maximumValue = height - 1)
        val middleHeight = (height * (clampedMiddlePercent / 100f)).roundToInt().coerceAtLeast(1)
        val middleEnd = (middleStart + middleHeight).coerceIn(minimumValue = middleStart + 1, maximumValue = height)
        val combinedHeight = topHeight + (middleEnd - middleStart)

        if (combinedHeight <= 0) {
            return null
        }

        return Bitmap.createBitmap(width, combinedHeight, Bitmap.Config.RGB_565).also { combinedBitmap ->
            val canvas = Canvas(combinedBitmap)
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(
                sourceBitmap,
                Rect(0, 0, width, topHeight),
                Rect(0, 0, width, topHeight),
                null,
            )
            canvas.drawBitmap(
                sourceBitmap,
                Rect(0, middleStart, width, middleEnd),
                Rect(0, topHeight, width, combinedHeight),
                null,
            )
        }
    }

    private companion object {
        const val ScreenshotTimeoutMs = 5_000L
        const val CaptureAttempts = 2
        const val CaptureRetryDelayMs = 150L

        /** Platform cooldown between takeScreenshot requests on the same display. */
        const val ScreenshotMinIntervalMs = 1_000L

        // Process-wide: AccessibilityService.takeScreenshot enforces one in-flight request
        // per display, and a failed request usually needs a cooldown before it can succeed.
        val CaptureMutex = Mutex()

        @Volatile
        var lastCaptureAttemptAtMs = 0L
    }
}
