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

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Process
import android.os.SystemClock
import com.haramveil.data.models.ModelDownloadManager
import com.haramveil.utils.DispatcherProvider
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class FossOcrEngine(
    private val context: Context,
    private val modelDownloadManager: ModelDownloadManager,
    private val dispatcherProvider: DispatcherProvider = DispatcherProvider(),
) : OcrEngine {
    private val environment: OrtEnvironment? by lazy {
        runCatching { OrtEnvironment.getEnvironment() }.getOrNull()
    }
    private val sessionLock = Any()

    @Volatile
    private var cachedSession: OrtSession? = null

    @Volatile
    private var cachedCharacters: List<String>? = null

    override suspend fun extractText(bitmap: Bitmap): OcrResult =
        withContext(dispatcherProvider.default) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)

            val emptyResult = OcrResult(
                text = "",
                confidence = 0f,
                processingTimeMs = 0L,
            )

            if (!isAvailable()) {
                modelDownloadManager.postSetupReminderNotification()
                return@withContext emptyResult
            }

            val ortEnvironment = environment
            if (ortEnvironment == null) {
                postSetupReminder()
                return@withContext emptyResult
            }

            val session = openSession()
            if (session == null) {
                postSetupReminder()
                return@withContext emptyResult
            }

            val startedAt = SystemClock.elapsedRealtime()
            val characters = loadCharacters(session)
            val inputEntry = session.inputInfo.entries
                .firstOrNull { entry -> entry.value.info is TensorInfo }
                ?: return@withContext emptyResult
            val inputShape = (inputEntry.value.info as? TensorInfo)?.shape
                ?: return@withContext emptyResult
            val targetHeight = when {
                inputShape.size > 2 && inputShape[2] > 0L -> inputShape[2].toInt()
                else -> DefaultInputHeight
            }
            val targetWidth = when {
                inputShape.size > 3 && inputShape[3] > 0L -> inputShape[3].toInt()
                else -> DefaultInputWidth
            }

            val recognizedText = mutableListOf<String>()
            val confidenceScores = mutableListOf<Float>()

            extractCandidateSlices(bitmap).forEach { lineBitmap ->
                try {
                    val inputData = preprocessBitmap(
                        bitmap = lineBitmap,
                        targetHeight = targetHeight,
                        targetWidth = targetWidth,
                    )
                    val tensorShape = longArrayOf(1L, 3L, targetHeight.toLong(), targetWidth.toLong())
                    OnnxTensor.createTensor(ortEnvironment, FloatBuffer.wrap(inputData), tensorShape).use { tensor ->
                        session.run(mapOf(inputEntry.key to tensor)).use { outputs ->
                            val decodedLine = decodeCtcOutput(
                                rawOutput = outputs[0].value,
                                characters = characters,
                            )
                            if (decodedLine.text.isNotBlank()) {
                                recognizedText += decodedLine.text
                                confidenceScores += decodedLine.confidence
                            }
                        }
                    }
                } finally {
                    recycleIfOwned(lineBitmap, bitmap)
                }
            }

            OcrResult(
                text = recognizedText.distinct().joinToString(separator = "\n"),
                confidence = confidenceScores.average().takeIf { !it.isNaN() }?.toFloat() ?: 0f,
                processingTimeMs = SystemClock.elapsedRealtime() - startedAt,
            )
        }

    override fun isAvailable(): Boolean = modelDownloadManager.modelFile().isFile

    fun close() {
        synchronized(sessionLock) {
            cachedSession?.close()
            cachedSession = null
            cachedCharacters = null
        }
    }

    private fun openSession(): OrtSession? {
        cachedSession?.let { return it }

        synchronized(sessionLock) {
            cachedSession?.let { return it }
            val ortEnvironment = environment ?: return null
            return runCatching {
                OrtSession.SessionOptions().use { sessionOptions ->
                    ortEnvironment.createSession(
                        modelDownloadManager.modelFile().absolutePath,
                        sessionOptions,
                    )
                }
            }.getOrNull()?.also { cachedSession = it }
        }
    }

    private fun postSetupReminder() {
        runCatching { modelDownloadManager.postSetupReminderNotification() }
    }

    private fun loadCharacters(session: OrtSession): List<String> {
        cachedCharacters?.let { return it }

        synchronized(sessionLock) {
            cachedCharacters?.let { return it }

            val metadataValue = resolveCharacterMetadata(session)
            val parsedCharacters = parseCharacterMetadata(metadataValue)
            cachedCharacters = parsedCharacters
            return parsedCharacters
        }
    }

    private fun resolveCharacterMetadata(session: OrtSession): String? {
        val metadata = runCatching {
            session.javaClass.getMethod("getMetadata").invoke(session)
        }.getOrNull() ?: return null

        val customMetadata = sequenceOf("getCustomMetadata", "getCustomMetadataMap")
            .mapNotNull { methodName ->
                runCatching {
                    metadata.javaClass.getMethod(methodName).invoke(metadata) as? Map<*, *>
                }.getOrNull()
            }
            .firstOrNull()

        return customMetadata?.get("character")?.toString()
    }

    private fun parseCharacterMetadata(rawMetadata: String?): List<String> {
        val baseCharacters = rawMetadata
            ?.let { metadata -> splitCharacterMetadata(metadata) }
            ?.filter(String::isNotEmpty)
            ?.toMutableList()
            ?: mutableListOf()

        if (baseCharacters.isEmpty()) {
            return listOf("blank", " ")
        }

        if (baseCharacters.first() != "blank") {
            baseCharacters.add(0, "blank")
        }
        if (baseCharacters.last() != " ") {
            baseCharacters.add(" ")
        }
        return baseCharacters
    }

    private fun splitCharacterMetadata(rawMetadata: String): List<String> {
        val quotedCharacters = runCatching { splitQuotedCharacters(rawMetadata) }.getOrNull()
        if (!quotedCharacters.isNullOrEmpty()) {
            return quotedCharacters
        }
        return splitLegacyCharacters(rawMetadata)
    }

    private fun splitQuotedCharacters(rawMetadata: String): List<String> {
        val body = rawMetadata.trim().removePrefix("[").removeSuffix("]")
        val characters = mutableListOf<String>()
        val currentToken = StringBuilder()
        var foundQuotedEntry = false
        var index = 0

        while (index < body.length) {
            val character = body[index]
            when {
                character == QuoteCharacter || character == AlternateQuoteCharacter -> {
                    val closingIndex = findClosingQuote(body, index, character)
                    currentToken.setLength(0)
                    if (closingIndex < 0) {
                        index = body.length
                    } else {
                        currentToken.append(body, index + 1, closingIndex)
                        characters += currentToken.toString()
                        currentToken.setLength(0)
                        foundQuotedEntry = true
                        index = skipToNextSeparator(body, closingIndex + 1)
                    }
                }

                character == EscapeCharacter && index + 1 < body.length -> {
                    currentToken.append(unescapeMetadataCharacter(body[index + 1]))
                    index += 2
                }

                character == EntrySeparator -> {
                    val token = unquoteToken(currentToken.toString())
                    if (token.isNotEmpty()) {
                        characters += token
                    }
                    currentToken.setLength(0)
                    index += 1
                }

                else -> {
                    currentToken.append(character)
                    index += 1
                }
            }
        }

        if (!foundQuotedEntry) {
            return emptyList()
        }

        val trailingToken = unquoteToken(currentToken.toString())
        if (trailingToken.isNotEmpty()) {
            characters += trailingToken
        }
        return characters
    }

    private fun findClosingQuote(
        value: String,
        openIndex: Int,
        quoteCharacter: Char,
    ): Int {
        var index = openIndex + 1
        while (index < value.length) {
            val character = value[index]
            if (character == EscapeCharacter && index + 1 < value.length) {
                index += 2
            } else if (character == quoteCharacter) {
                return index
            } else {
                index += 1
            }
        }
        return -1
    }

    private fun skipToNextSeparator(
        value: String,
        startIndex: Int,
    ): Int {
        var index = startIndex
        while (index < value.length && value[index] != EntrySeparator) {
            index += 1
        }
        return index + 1
    }

    private fun unescapeMetadataCharacter(character: Char): Char =
        when (character) {
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            else -> character
        }

    private fun unquoteToken(token: String): String =
        token.trim().trim(QuoteCharacter, AlternateQuoteCharacter)

    private fun splitLegacyCharacters(rawMetadata: String): List<String> =
        rawMetadata
            .replace("[", "")
            .replace("]", "")
            .lineSequence()
            .flatMap { line ->
                when {
                    line.contains(EntrySeparator) -> line.split(EntrySeparator).asSequence()
                    else -> sequenceOf(line)
                }
            }
            .map { token -> unquoteToken(token) }
            .toList()

    private fun extractCandidateSlices(bitmap: Bitmap): List<Bitmap> {
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            return emptyList()
        }

        val sliceHeight = (bitmap.height / 6).coerceIn(minimumValue = 48, maximumValue = 160)
        val sliceStep = max(sliceHeight / 2, 24)
        val slices = mutableListOf<Bitmap>()

        var top = 0
        while (top < bitmap.height) {
            val height = min(sliceHeight, bitmap.height - top)
            if (height <= 0) {
                break
            }

            val slice = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, height)
            if (hasMeaningfulContrast(slice)) {
                slices += slice
            } else {
                recycleIfOwned(slice, bitmap)
            }

            if (top + height >= bitmap.height) {
                break
            }
            top += sliceStep
        }

        if (slices.isEmpty()) {
            slices += Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height)
        }

        return slices
    }

    private fun recycleIfOwned(
        candidate: Bitmap,
        source: Bitmap,
    ) {
        if (candidate !== source && !candidate.isRecycled) {
            candidate.recycle()
        }
    }

    private fun hasMeaningfulContrast(bitmap: Bitmap): Boolean {
        val widthStep = max(bitmap.width / 12, 1)
        val heightStep = max(bitmap.height / 6, 1)
        var minLuma = 255
        var maxLuma = 0

        for (y in 0 until bitmap.height step heightStep) {
            for (x in 0 until bitmap.width step widthStep) {
                val pixel = bitmap.getPixel(x, y)
                val luma = ((Color.red(pixel) * 299) + (Color.green(pixel) * 587) + (Color.blue(pixel) * 114)) / 1000
                minLuma = min(minLuma, luma)
                maxLuma = max(maxLuma, luma)
            }
        }

        return (maxLuma - minLuma) >= MinimumContrastDelta
    }

    private fun preprocessBitmap(
        bitmap: Bitmap,
        targetHeight: Int,
        targetWidth: Int,
    ): FloatArray {
        val scaledWidth = min(
            max((bitmap.width * (targetHeight / bitmap.height.toFloat())).roundToInt(), 1),
            targetWidth,
        )
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, targetHeight, true)
        return try {
            val pixelCount = scaledWidth * targetHeight
            val pixels = IntArray(pixelCount)
            resizedBitmap.getPixels(pixels, 0, scaledWidth, 0, 0, scaledWidth, targetHeight)

            val channelSize = targetHeight * targetWidth
            val tensorData = FloatArray(channelSize * 3)

            for (y in 0 until targetHeight) {
                for (x in 0 until scaledWidth) {
                    val pixel = pixels[(y * scaledWidth) + x]
                    val offset = (y * targetWidth) + x

                    tensorData[offset] = normalizeChannel(Color.red(pixel))
                    tensorData[channelSize + offset] = normalizeChannel(Color.green(pixel))
                    tensorData[(channelSize * 2) + offset] = normalizeChannel(Color.blue(pixel))
                }
            }

            tensorData
        } finally {
            if (!resizedBitmap.isRecycled && resizedBitmap !== bitmap) {
                resizedBitmap.recycle()
            }
        }
    }

    private fun normalizeChannel(channelValue: Int): Float =
        ((channelValue / 255f) - 0.5f) / 0.5f

    private fun decodeCtcOutput(
        rawOutput: Any?,
        characters: List<String>,
    ): DecodedLine {
        val timeSteps = flattenTimeSteps(rawOutput)
        if (timeSteps.isEmpty()) {
            return DecodedLine(
                text = "",
                confidence = 0f,
            )
        }

        val outputText = StringBuilder()
        val confidences = mutableListOf<Float>()
        var previousIndex = -1

        timeSteps.forEach { logits ->
            val (bestIndex, probability) = findBestIndex(logits)
            if (bestIndex == BlankTokenIndex || bestIndex == previousIndex) {
                previousIndex = bestIndex
                return@forEach
            }

            val token = characters.getOrNull(bestIndex).orEmpty()
            if (token.isNotEmpty()) {
                outputText.append(token)
                confidences += probability
            }
            previousIndex = bestIndex
        }

        return DecodedLine(
            text = outputText.toString().trim(),
            confidence = confidences.average().takeIf { !it.isNaN() }?.toFloat() ?: 0f,
        )
    }

    private fun flattenTimeSteps(rawOutput: Any?): List<FloatArray> =
        when (rawOutput) {
            is FloatArray -> listOf(rawOutput)
            is Array<*> -> {
                val directRows = rawOutput.filterIsInstance<FloatArray>()
                if (directRows.size == rawOutput.size && directRows.isNotEmpty()) {
                    directRows
                } else {
                    rawOutput.flatMap(::flattenTimeSteps)
                }
            }

            else -> emptyList()
        }

    private fun findBestIndex(logits: FloatArray): Pair<Int, Float> {
        var bestIndex = 0
        var bestLogit = Float.NEGATIVE_INFINITY

        logits.forEachIndexed { index, logit ->
            if (logit > bestLogit) {
                bestLogit = logit
                bestIndex = index
            }
        }

        val denominator = logits.sumOf { logit -> exp((logit - bestLogit).toDouble()) }
        val probability = if (denominator == 0.0) {
            0f
        } else {
            (1.0 / denominator).toFloat()
        }
        return bestIndex to probability
    }

    private data class DecodedLine(
        val text: String,
        val confidence: Float,
    )

    private companion object {
        const val BlankTokenIndex = 0
        const val DefaultInputHeight = 48
        const val DefaultInputWidth = 320
        const val MinimumContrastDelta = 18
        const val EntrySeparator = ','
        const val QuoteCharacter = '\''
        const val AlternateQuoteCharacter = '"'
        const val EscapeCharacter = '\\'
    }
}
