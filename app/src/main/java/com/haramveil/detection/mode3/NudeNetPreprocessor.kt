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

package com.haramveil.detection.mode3

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.haramveil.utils.DispatcherProvider
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.roundToInt

class NudeNetPreprocessor(
    private val dispatcherProvider: DispatcherProvider = DispatcherProvider(),
) {

    suspend fun createTensor(
        bitmap: Bitmap,
        modelConfig: ModelConfig,
        environment: OrtEnvironment,
    ): OnnxTensor = withContext(dispatcherProvider.default) {
        val inputSize = modelConfig.inputSize
        val letterboxed = letterbox(bitmap, inputSize)
        try {
            val pixels = IntArray(inputSize * inputSize)
            letterboxed.getPixels(
                pixels,
                0,
                inputSize,
                0,
                0,
                inputSize,
                inputSize,
            )

            val channelSize = inputSize * inputSize
            val inputData = FloatArray(channelSize * 3)

            for (index in pixels.indices) {
                val pixel = pixels[index]
                inputData[index] = ((pixel shr 16) and 0xFF) / 255f
                inputData[channelSize + index] = ((pixel shr 8) and 0xFF) / 255f
                inputData[(channelSize * 2) + index] = (pixel and 0xFF) / 255f
            }

            OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(inputData),
                longArrayOf(1L, 3L, inputSize.toLong(), inputSize.toLong()),
            )
        } finally {
            // Only the temporary letterbox bitmap is recycled: the caller's bitmap stays
            // usable because Mode3Processor still owns it (it reuses or recycles it later).
            letterboxed.recycle()
        }
    }

    /**
     * Aspect-preserving centred letterbox padded with grey (114), matching the Ultralytics
     * pre-processing the model was exported with. Stretching the frame to a square instead
     * shifts every predicted box away from the body part it was trained to detect.
     */
    private fun letterbox(
        bitmap: Bitmap,
        inputSize: Int,
    ): Bitmap {
        val scale = max(
            inputSize.toFloat() / bitmap.width.toFloat(),
            inputSize.toFloat() / bitmap.height.toFloat(),
        )
        val scaledWidth = max(1, (bitmap.width * scale).roundToInt())
        val scaledHeight = max(1, (bitmap.height * scale).roundToInt())
        val leftOffset = (inputSize - scaledWidth) / 2
        val topOffset = (inputSize - scaledHeight) / 2

        val letterboxed = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        Canvas(letterboxed).apply {
            drawColor(Color.rgb(PaddingGrey, PaddingGrey, PaddingGrey))
            drawBitmap(
                bitmap,
                Rect(0, 0, bitmap.width, bitmap.height),
                Rect(leftOffset, topOffset, leftOffset + scaledWidth, topOffset + scaledHeight),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
        }
        return letterboxed
    }

    private companion object {
        const val PaddingGrey = 114
    }
}
