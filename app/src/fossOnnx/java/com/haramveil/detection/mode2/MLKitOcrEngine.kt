package com.haramveil.detection.mode2

import android.content.Context
import android.graphics.Bitmap
import com.haramveil.utils.DispatcherProvider

class MLKitOcrEngine(
    private val context: Context,
    private val dispatcherProvider: DispatcherProvider = DispatcherProvider(),
) : OcrEngine {
    override suspend fun extractText(bitmap: Bitmap): OcrResult = OcrResult("", 0f, 0L)
    override fun isAvailable(): Boolean = false
    fun close() {}
}
