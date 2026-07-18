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
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.os.SystemClock
import com.haramveil.data.models.ModelSource
import com.haramveil.data.models.VisualModelOption
import com.haramveil.utils.DispatcherProvider
import com.haramveil.utils.DebugLog
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.exp
import kotlin.math.max

data class OnnxOutput(
    val classScores: Map<String, Float>,
    val inferenceTimeMs: Long,
)

data class DetectionResult(
    val isUnsafe: Boolean,
    val confidence: Float,
    val className: String,
    val inferenceTimeMs: Long,
)

class NudeNetInferenceEngine(
    private val context: Context,
    private val dispatcherProvider: DispatcherProvider = DispatcherProvider(),
) {
    private val environment = OrtEnvironment.getEnvironment()
    private val sessionLock = Any()

    @Volatile
    private var activeSession: OrtSession? = null

    @Volatile
    private var activeModelConfig: ModelConfig? = null

    @Volatile
    private var cachedModelFile: File? = null

    suspend fun warm(modelConfig: ModelConfig) {
        withContext(dispatcherProvider.default) {
            ensureSession(modelConfig)
        }
    }

    suspend fun run(
        tensor: OnnxTensor,
        modelConfig: ModelConfig,
    ): OnnxOutput = withContext(dispatcherProvider.default) {
        val session = ensureSession(modelConfig)
        val inputEntry = session.inputInfo.entries.first()
        val outputInfo = session.outputInfo.entries.firstOrNull()?.value?.info as? TensorInfo
        val startedAt = SystemClock.elapsedRealtime()

        tensor.use { inputTensor ->
            session.run(mapOf(inputEntry.key to inputTensor)).use { outputs ->
                val firstOutput = outputs.firstOrNull()?.value
                val resolvedLabels = resolveLabels(
                    session = session,
                    outputInfo = outputInfo,
                    rawOutput = firstOutput,
                )

                OnnxOutput(
                    classScores = parseOutputScores(
                        rawOutput = firstOutput,
                        labels = resolvedLabels,
                    ),
                    inferenceTimeMs = SystemClock.elapsedRealtime() - startedAt,
                )
            }
        }
    }
    fun evaluate(
        output: OnnxOutput,
        threshold: Float = DefaultUnsafeThreshold,
    ): DetectionResult {
        val topScore = output.classScores.maxByOrNull { it.value }
        DebugLog.d(context, "HaramVeilInference") { "Top candidate: ${topScore?.key} score=${topScore?.value} (threshold=$threshold)" }

        val unsafeCandidate = output.classScores
            .filterKeys(::isUnsafeClassLabel)
            .maxByOrNull { it.value }

        return if (unsafeCandidate != null && unsafeCandidate.value >= threshold) {
            DetectionResult(
                isUnsafe = true,
                confidence = unsafeCandidate.value,
                className = unsafeCandidate.key,
                inferenceTimeMs = output.inferenceTimeMs,
            )
        } else {
            DetectionResult(
                isUnsafe = false,
                confidence = topScore?.value ?: 0f,
                className = topScore?.key ?: "SAFE",
                inferenceTimeMs = output.inferenceTimeMs,
            )
        }
    }

    fun environment(): OrtEnvironment = environment

    fun close() {
        synchronized(sessionLock) {
            activeSession?.close()
            activeSession = null
            activeModelConfig = null
            cachedModelFile?.delete()
            cachedModelFile = null
        }
    }

    private fun ensureSession(modelConfig: ModelConfig): OrtSession {
        activeSession?.let { session ->
            if (activeModelConfig == modelConfig) {
                return session
            }
        }

        synchronized(sessionLock) {
            activeSession?.let { session ->
                if (activeModelConfig == modelConfig) {
                    return session
                }
            }

            activeSession?.close()
            cachedModelFile?.delete()
            val modelFile = resolveModelFile(modelConfig)
            val session = OrtSession.SessionOptions().use { sessionOptions ->
                sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                environment.createSession(modelFile.absolutePath, sessionOptions)
            }

            // Only cache the file for deletion if we created a copy in cacheDir.
            // Imported models in filesDir should NOT be deleted on session close.
            if (modelFile.parentFile == context.cacheDir) {
                cachedModelFile = modelFile
            } else {
                cachedModelFile = null
            }
            activeModelConfig = modelConfig
            activeSession = session
            return session
        }
    }

    /**
     * Resolves the on-disk [File] for the given [modelConfig].
     *
     * - Bundled assets are copied from `assets/` into `cacheDir/` (same as before).
     * - Imported files live in `filesDir/models/` and are loaded directly without copying.
     */
    private fun resolveModelFile(modelConfig: ModelConfig): File {
        val visualModel = modelConfig.visualModel
        return when (visualModel.source) {
            ModelSource.BUNDLED_ASSET -> {
                val assetName = visualModel.assetName
                    ?: error("Bundled model ${visualModel.name} has no asset name")
                copyAssetToCache(assetName)
            }
            ModelSource.IMPORTED_FILE -> {
                val fileName = visualModel.assetName
                    ?: error("Imported model ${visualModel.name} has no file name")
                File(context.filesDir, "models/$fileName").also { file ->
                    check(file.exists()) {
                        "Imported model ${visualModel.name} is not installed at ${file.absolutePath}"
                    }
                }
            }
        }
    }

    private fun copyAssetToCache(assetName: String): File {
        val cachedFile = File(context.cacheDir, assetName)
        context.assets.open(assetName).use { inputStream ->
            cachedFile.outputStream().use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }
        return cachedFile
    }

    private fun resolveLabels(
        session: OrtSession,
        outputInfo: TensorInfo?,
        rawOutput: Any?,
    ): List<String> {
        resolveMetadataLabels(session)?.let { labels ->
            if (labels.isNotEmpty()) {
                return labels
            }
        }

        val vectorLength = inferLabelCountFromOutput(outputInfo, rawOutput)
        return when {
            vectorLength == 2 -> listOf("SAFE", "UNSAFE")
            vectorLength in 1..DefaultNudeNetLabels.size -> DefaultNudeNetLabels.take(vectorLength)
            vectorLength > 0 -> List(vectorLength) { index -> "UNSAFE_CLASS_${index + 1}" }
            else -> DefaultNudeNetLabels
        }
    }

    private fun resolveMetadataLabels(session: OrtSession): List<String>? {
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
            ?: return null

        val rawLabels = sequenceOf("classes", "labels", "names", "label_map")
            .mapNotNull { key -> customMetadata[key]?.toString() }
            .firstOrNull()
            ?: return null

        return rawLabels
            .replace("[", "")
            .replace("]", "")
            .lineSequence()
            .flatMap { line ->
                when {
                    line.contains(',') -> line.split(',').asSequence()
                    else -> sequenceOf(line)
                }
            }
            .map { token -> token.trim().trim('"', '\'') }
            .filter(String::isNotBlank)
            .toList()
            .ifEmpty { null }
    }

    private fun inferLabelCountFromOutput(
        outputInfo: TensorInfo?,
        rawOutput: Any?,
    ): Int {
        val outputShape = outputInfo?.shape
            ?.toList()
            ?.filter { dimension -> dimension > 0L }
            ?: emptyList()
        outputShape.firstOrNull { dimension -> dimension in 1L..64L }?.let { smallDimension ->
            return smallDimension.toInt()
        }

        return when (rawOutput) {
            is FloatArray -> rawOutput.size
            is Array<*> -> rawOutput.firstOrNull()?.let { inferLabelCountFromOutput(null, it) } ?: 0
            else -> 0
        }
    }

    private fun parseOutputScores(
        rawOutput: Any?,
        labels: List<String>,
    ): Map<String, Float> {
        // The bundled/supported models (e.g. 320n.onnx) are Ultralytics YOLOv8 *detection*
        // exports, NOT flat classifiers. Their output is [1, C, N] where
        // C = 4 (box) + 1 (objectness) + K (class logits). We decode the per-anchor
        // detections and report the maximum class probability (scaled by objectness) for
        // each class. A naive classification-style parse of this tensor returns garbage
        // (it reads box coordinates as if they were class scores) and never blocks.
        val decoded = decodeYoloOutput(rawOutput, labels)
        if (decoded.isNotEmpty()) {
            return decoded
        }

        // Fallback: models that genuinely emit a flat classification vector.
        return when (rawOutput) {
            is FloatArray -> mapScoreVector(rawOutput, labels)
            is Array<*> -> parseNestedOutput(rawOutput, labels)
            is Map<*, *> -> rawOutput.entries
                .mapNotNull { entry ->
                    val label = entry.key?.toString() ?: return@mapNotNull null
                    val score = (entry.value as? Number)?.toFloat() ?: return@mapNotNull null
                    label to normalizeScore(score)
                }
                .toMap()

            else -> emptyMap()
        }
    }

    /**
     * Decodes an Ultralytics YOLOv8 detection tensor into `className -> maxScore`.
     *
     * Accepts the flattened [FloatArray] of length `S * C * N` that OnnxRuntime Java
     * returns from [OnnxTensor.getValue] for a 3D float tensor, where
     * `C = 4 + 1 + K` (box, objectness, K class logits) and `N` is the number of
     * anchor predictions. Only channels beyond the first 5 are treated as class logits, so
     * this also works for models that append extra outputs (e.g. 22 = 4 + 1 + 17).
     */
    private fun decodeYoloOutput(
        rawOutput: Any?,
        labels: List<String>,
    ): Map<String, Float> {
        val layout = extractYoloLayout(rawOutput) ?: return emptyMap()
        val (channels, anchors, flat) = layout
        // Class logits occupy channels 5..(channels-1). The runtime channel count is
        // authoritative (driven by the model) and may differ from the label list size
        // when the model metadata lists a stale/extra class name.
        val classCount = (channels - 5).coerceAtLeast(0)
        if (classCount <= 0 || anchors <= 0) {
            return emptyMap()
        }

        // Surface a model/label mismatch: if the label list size disagrees with the
        // tensor-derived class count, detections beyond the label range would be dropped
        // (and never matched as unsafe), so log it for debugging.
        if (classCount != labels.size) {
            DebugLog.w(context, "HaramVeilInference") {
                "Model class count ($classCount) differs from label list size (${labels.size}); " +
                    "extra classes will be reported as UNSAFE_CLASS_* and not matched as unsafe."
            }
        }

        val classScores = mutableMapOf<String, Float>()
        for (anchor in 0 until anchors) {
            val base = anchor * channels
            // Objectness is the 5th channel (index 4).
            val objectness = normalizeScore(flat[base + 4])
            if (objectness <= ObjectnessGate) {
                continue
            }
            for (classIndex in 0 until classCount) {
                val label = labels.getOrNull(classIndex) ?: "UNSAFE_CLASS_${classIndex + 1}"
                val classProb = normalizeScore(flat[base + 5 + classIndex])
                val score = classProb * objectness
                val current = classScores[label] ?: 0f
                if (score > current) {
                    classScores[label] = score
                }
            }
        }
        return classScores
    }

    /**
     * Normalizes a YOLO output tensor into `(channels, anchors, flatFloatArray)` or returns
     * `null` when the shape is not a recognizable detection layout.
     *
     * For an Ultralytics YOLOv8 detection head the channel count is `5 + K`
     * (4 box coordinates + 1 objectness + K class logits). We recover it from the
     * flattened tensor length by trying class counts in a sane range (1..64) and
     * picking the one whose `5 + K` divides the total element count. This avoids
     * relying on the (sometimes stale) class count in the model metadata.
     */
    private fun extractYoloLayout(
        rawOutput: Any?,
    ): Triple<Int, Int, FloatArray>? {
        // OnnxRuntime Java returns a 3D float tensor as a flattened FloatArray
        // (S * C * N elements). We only handle that representation here.
        val flat = (rawOutput as? FloatArray) ?: return null
        if (flat.isEmpty()) {
            return null
        }

        // total = channels * anchors, with channels = 5 + K (K = class count).
        //
        // Several values of K can divide `total` (e.g. for the 320n model total=46200 is
        // divisible by 6 as well as 22), so we must not return on the first match. For a
        // YOLO head the number of anchors is far larger than the channel count, so we pick
        // the split with the MOST anchors (i.e. the SMALLEST channel count >= 5). This
        // unambiguously resolves to 22 channels / 2100 anchors for the shipped model.
        val total = flat.size
        var best: Triple<Int, Int, FloatArray>? = null
        for (classCount in 1..64) {
            val channels = 5 + classCount
            if (total % channels != 0) continue
            val anchors = total / channels
            if (anchors <= 0) continue
            // Disambiguate from non-YOLO divisors (e.g. total=46200 is also divisible by 6,
            // which would yield channels=6/anchors=7700). Ultralytics YOLOv8 uses three
            // detection scales at strides 8/16/32, so the anchor count is always
            // 21 * k^2 ( = (4k)^2 + (2k)^2 + k^2 ) for some integer k. Require that.
            if (!isYoloAnchorCount(anchors)) continue
            best = Triple(channels, anchors, flat)
            break
        }
        return best
    }

    /**
     * True when [anchors] matches the Ultralytics YOLOv8 anchor-grid layout:
     * (H/8)^2 + (H/16)^2 + (H/32)^2 = 16k^2 + 4k^2 + k^2 = 21 * k^2.
     */
    private fun isYoloAnchorCount(anchors: Int): Boolean {
        if (anchors <= 0 || anchors % 21 != 0) return false
        val kSquared = anchors / 21
        val k = kSquared.toDouble().let { Math.sqrt(it) }
        return k >= 1.0 && k == k.toInt().toDouble()
    }
    private fun parseNestedOutput(
        rawOutput: Array<*>,
        labels: List<String>,
    ): Map<String, Float> {
        val floatRows = rawOutput.filterIsInstance<FloatArray>()
        if (floatRows.size == rawOutput.size && floatRows.isNotEmpty()) {
            return parse2dOutput(floatRows, labels)
        }

        val nestedArrays = rawOutput.filterIsInstance<Array<*>>()
        if (nestedArrays.isNotEmpty()) {
            return parseNestedOutput(nestedArrays.first(), labels)
        }

        return emptyMap()
    }

    private fun parse2dOutput(
        rows: List<FloatArray>,
        labels: List<String>,
    ): Map<String, Float> {
        if (rows.size == 1) {
            return mapScoreVector(rows.first(), labels)
        }

        val rowLength = rows.firstOrNull()?.size ?: return emptyMap()
        val likelyByColumns = rows.size in 6..64 && rowLength > 64
        val likelyByRows = rows.size > 64 && rowLength in 6..64

        return when {
            likelyByColumns -> parseDetectionColumns(rows, labels)
            likelyByRows -> parseDetectionRows(rows, labels)
            rowLength == labels.size || rowLength == labels.size + 1 -> {
                rows.fold(emptyMap()) { accumulator, row ->
                    mergeScores(
                        accumulator,
                        mapScoreVector(row.take(labels.size).toFloatArray(), labels),
                    )
                }
            }

            else -> mapScoreVector(
                rows.flatMap { row -> row.toList() }.take(labels.size).toFloatArray(),
                labels,
            )
        }
    }

    private fun parseDetectionRows(
        detections: List<FloatArray>,
        labels: List<String>,
    ): Map<String, Float> {
        val scores = mutableMapOf<String, Float>()
        detections.forEach { detection ->
            val classOffset = when {
                detection.size >= labels.size + 5 -> 5
                detection.size >= labels.size + 4 -> 4
                else -> return@forEach
            }
            val objectness = detection.getOrNull(4)
                ?.takeIf { detection.size >= labels.size + 5 }
                ?.let(::normalizeScore)
                ?: 1f
            val classCount = minOf(labels.size, detection.size - classOffset)

            for (classIndex in 0 until classCount) {
                val label = labels[classIndex]
                val score = normalizeScore(detection[classOffset + classIndex]) * objectness
                scores[label] = max(scores[label] ?: 0f, score)
            }
        }
        return scores
    }

    private fun parseDetectionColumns(
        featuresByDetection: List<FloatArray>,
        labels: List<String>,
    ): Map<String, Float> {
        val detectionCount = featuresByDetection.firstOrNull()?.size ?: return emptyMap()
        val classOffset = when {
            featuresByDetection.size >= labels.size + 5 -> 5
            featuresByDetection.size >= labels.size + 4 -> 4
            else -> return emptyMap()
        }
        val scores = mutableMapOf<String, Float>()
        val objectnessRow = if (featuresByDetection.size >= labels.size + 5) {
            featuresByDetection.getOrNull(4)
        } else {
            null
        }
        val classCount = minOf(labels.size, featuresByDetection.size - classOffset)

        for (detectionIndex in 0 until detectionCount) {
            val objectness = objectnessRow
                ?.getOrNull(detectionIndex)
                ?.let(::normalizeScore)
                ?: 1f

            for (classIndex in 0 until classCount) {
                val label = labels[classIndex]
                val score = normalizeScore(
                    featuresByDetection[classOffset + classIndex][detectionIndex],
                ) * objectness
                scores[label] = max(scores[label] ?: 0f, score)
            }
        }
        return scores
    }

    private fun mapScoreVector(
        scores: FloatArray,
        labels: List<String>,
    ): Map<String, Float> =
        buildMap {
            scores.forEachIndexed { index, score ->
                val label = labels.getOrNull(index) ?: "UNSAFE_CLASS_${index + 1}"
                put(label, normalizeScore(score))
            }
        }

    private fun mergeScores(
        left: Map<String, Float>,
        right: Map<String, Float>,
    ): Map<String, Float> =
        (left.keys + right.keys).associateWith { label ->
            max(left[label] ?: 0f, right[label] ?: 0f)
        }

    private fun normalizeScore(rawScore: Float): Float {
        return if (rawScore in 0f..1f) {
            rawScore
        } else {
            (1.0 / (1.0 + exp(-rawScore.toDouble()))).toFloat()
        }
    }

    private fun isUnsafeClassLabel(label: String): Boolean {
        val normalized = label.lowercase()
        return UnsafeClassMarkers.any { marker -> marker in normalized }
    }

    private companion object {
        const val DefaultUnsafeThreshold = 0.35f
        const val ObjectnessGate = 0.25f

        val UnsafeClassMarkers = setOf(
            "unsafe",
            "explicit",
            "exposed",
            "genitalia",
            "breast",
            "buttocks",
            "anus",
        )

        val DefaultNudeNetLabels = listOf(
            "FEMALE_GENITALIA_COVERED",
            "FACE_FEMALE",
            "BUTTOCKS_EXPOSED",
            "FEMALE_BREAST_EXPOSED",
            "FEMALE_GENITALIA_EXPOSED",
            "MALE_BREAST_EXPOSED",
            "ANUS_EXPOSED",
            "FEET_EXPOSED",
            "BELLY_COVERED",
            "FEET_COVERED",
            "ARMPITS_COVERED",
            "ARMPITS_EXPOSED",
            "FACE_MALE",
            "BELLY_EXPOSED",
            "MALE_GENITALIA_EXPOSED",
            "ANUS_COVERED",
            "FEMALE_BREAST_COVERED",
            "BUTTOCKS_COVERED",
        )
    }
}
