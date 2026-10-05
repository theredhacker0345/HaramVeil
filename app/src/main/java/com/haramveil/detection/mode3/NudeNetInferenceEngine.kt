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

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.os.SystemClock
import com.haramveil.data.models.ModelSource
import com.haramveil.utils.DispatcherProvider
import com.haramveil.utils.DebugLog
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import java.util.UUID
import kotlin.math.exp

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

    @Volatile
    private var closed = false

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
        val outputShape = resolveOutputShape(session)
        // The session is not thread safe: a second run() (or a close()) must never enter
        // while this one is executing.
        synchronized(sessionLock) {
            check(!closed) { ClosedMessage }
            val startedAt = SystemClock.elapsedRealtime()
            tensor.use { inputTensor ->
                session.run(mapOf(resolveInputName(session) to inputTensor)).use { outputs ->
                    // OrtSession.Result is Iterable<Map.Entry<String, OnnxValue>>: mapping the entries yields the
                    // raw tensor carrier. Reading `Result.value` or `Result.getValue()` would hand
                    // back the OnnxValue wrapper and every score decode would come back empty.
                    val rawOutput = outputs.map { entry -> entry.value }.firstOrNull()
                    val resolvedLabels = resolveLabels(
                        session = session,
                        shape = outputShape,
                        rawOutput = rawOutput,
                    )

                    OnnxOutput(
                        classScores = parseOutputScores(
                            rawOutput = rawOutput,
                            labels = resolvedLabels,
                            shape = outputShape,
                        ),
                        inferenceTimeMs = SystemClock.elapsedRealtime() - startedAt,
                    )
                }
            }
        }
    }

    fun evaluate(
        output: OnnxOutput,
        threshold: Float = DefaultUnsafeThreshold,
    ): DetectionResult {
        val topScore = output.classScores.maxByOrNull { it.value }
        DebugLog.d(context, LogTag) { "Top candidate: ${topScore?.key} score=${topScore?.value} (threshold=$threshold)" }

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
            if (closed) {
                return
            }
            closed = true
            activeSession?.close()
            activeSession = null
            activeModelConfig = null
            deleteOwnedModelFile(cachedModelFile)
            cachedModelFile = null
        }
    }

    private fun ensureSession(modelConfig: ModelConfig): OrtSession {
        val existingSession = activeSession
        if (!closed && existingSession != null && activeModelConfig == modelConfig) {
            return existingSession
        }

        synchronized(sessionLock) {
            check(!closed) { ClosedMessage }
            activeSession?.let { session ->
                if (activeModelConfig == modelConfig) {
                    return session
                }
            }

            activeSession?.close()
            activeSession = null
            activeModelConfig = null
            deleteOwnedModelFile(cachedModelFile)
            cachedModelFile = null

            val modelFile = resolveModelFile(modelConfig)
            val session = try {
                OrtSession.SessionOptions().use { sessionOptions ->
                    sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                    environment.createSession(modelFile.absolutePath, sessionOptions)
                }
            } catch (failure: Exception) {
                deleteOwnedModelFile(modelFile)
                rethrowFailure(failure)
            }

            // Only cache the file for deletion if we created a copy in cacheDir.
            // Imported models in filesDir should NOT be deleted on session close.
            cachedModelFile = modelFile.takeIf { candidate -> candidate.parentFile == context.cacheDir }
            activeModelConfig = modelConfig
            activeSession = session
            return session
        }
    }

    private fun resolveInputName(session: OrtSession): String {
        val declaredNames = session.inputInfo.keys
        return PreferredInputNames.firstOrNull { candidate -> candidate in declaredNames }
            ?: declaredNames.firstOrNull()
            ?: error("Visual model does not declare an input tensor.")
    }

    private fun resolveOutputShape(session: OrtSession): List<Long> {
        val tensorInfo = session.outputInfo.entries.firstOrNull()?.value?.info as? TensorInfo
        return tensorInfo?.shape?.toList().orEmpty()
    }

    private fun deleteOwnedModelFile(modelFile: File?) {
        if (modelFile != null && modelFile.parentFile == context.cacheDir) {
            modelFile.delete()
        }
    }

    private fun rethrowFailure(failure: Throwable): Nothing = throw failure

    /**
     * Resolves the on-disk [File] for the given [modelConfig].
     *
     * - Bundled assets are copied from `assets/` into a private `cacheDir/` file.
     * - Imported files live in `filesDir/models/` and are loaded directly without copying.
     */
    private fun resolveModelFile(modelConfig: ModelConfig): File {
        val visualModel = modelConfig.visualModel
        return when (visualModel.source) {
            ModelSource.BUNDLED_ASSET -> {
                val assetName = visualModel.assetName
                    ?: error("Bundled model ${visualModel.name} has no asset name")
                copyAssetToPrivateCache(assetName)
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

    /**
     * Copies a bundled asset into a private cache file. The name is unique per call so a
     * concurrent engine instance (or the benchmark runner, which copies the same asset)
     * can never truncate a file that another session already has open.
     */
    private fun copyAssetToPrivateCache(assetName: String): File {
        val cachedFile = File(context.cacheDir, "nudenet-${UUID.randomUUID()}-$assetName")
        try {
            context.assets.open(assetName).use { inputStream ->
                cachedFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        } catch (failure: Exception) {
            cachedFile.delete()
            rethrowFailure(failure)
        }
        return cachedFile
    }

    private fun resolveLabels(
        session: OrtSession,
        shape: List<Long>,
        rawOutput: Any?,
    ): List<String> {
        resolveMetadataLabels(session)?.let { labels ->
            if (labels.isNotEmpty()) {
                return labels
            }
        }

        val vectorLength = inferLabelCountFromOutput(shape, rawOutput)
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

        return parseLabelList(rawLabels)
    }

    /**
     * Parses a model label list. Ultralytics exports store `names` as a Python dict repr
     * (`{0: 'FEMALE_GENITALIA_COVERED', 1: 'FACE_FEMALE', ...}`), so the numeric indices
     * and the surrounding quotes/braces have to be stripped or every label ends up with a
     * `0: '` prefix that breaks class matching.
     */
    private fun parseLabelList(rawLabels: String): List<String> {
        val normalized = rawLabels.trim().removePrefix("{").removeSuffix("}")
        return normalized
            .split(',', '\n', '\r')
            .asSequence()
            .map { token -> token.trim().removePrefix(":").trim().trim('"', '\'', ' ', '[', ']') }
            .filter { token -> token.isNotBlank() && !token.all { character -> character.isDigit() } }
            .toList()
    }

    /**
     * Infers the class count of the primary output tensor. For a `[1, 4 + K, N]` detection
     * head the class count is the channel count minus the box columns; the batch dimension
     * must never be mistaken for the class dimension.
     */
    private fun inferLabelCountFromOutput(
        shape: List<Long>,
        rawOutput: Any?,
    ): Int {
        if (shape.size >= 3) {
            val channels = shape[shape.size - 2]
            val anchors = shape.last()
            if (channels > BoxChannelCount.toLong() && channels <= anchors && anchors > 1L) {
                return (channels - BoxChannelCount).toInt().coerceAtMost(MaxClassCount)
            }
        }

        val labelDimension = shape.lastOrNull { dimension -> dimension > 1L }
        if (labelDimension != null) {
            return labelDimension.toInt().coerceAtMost(MaxLabelCount)
        }

        val candidate = when (rawOutput) {
            is FloatArray -> rawOutput.size
            is Array<*> -> ((rawOutput.firstOrNull() as? Array<*>)?.firstOrNull() as? FloatArray)?.size ?: 0
            else -> 0
        }
        return candidate.takeIf { size -> size in 1..MaxLabelCount } ?: 0
    }

    private fun parseOutputScores(
        rawOutput: Any?,
        labels: List<String>,
        shape: List<Long>,
    ): Map<String, Float> {
        // The bundled/supported models (e.g. 320n.onnx) are Ultralytics YOLOv8 *detection*
        // exports, NOT flat classifiers: output0 is [1, 4 + K, N] with 4 box columns and K
        // class LOGITS (320n: [1, 22, 2100] = 4 box + 18 classes, no objectness column).
        // A classification-style parse reads box coordinates as class scores, so the layout
        // is derived from the tensor shape first and only a genuine 1D vector is treated as
        // a classification output.
        val floatValues = flattenFloatOutput(rawOutput)
        if (floatValues == null || floatValues.isEmpty()) {
            return emptyMap()
        }

        val dimensions = shape.filter { dimension -> dimension > 0L }.map { dimension -> dimension.toInt() }
        val decoded = decodeDetections(floatValues, dimensions, labels)
            ?: mapScoreVector(floatValues, labels)

        // ConfidenceGate keeps noise anchors from winning: because the decoders report the
        // per-class maximum, gating the reported score gates every anchor of that class.
        return decoded.filterValues { score -> score >= ConfidenceGate }
    }

    /**
     * Decodes a detection tensor into `className -> maxScore`.
     *
     * No NMS is applied on purpose: the values consumed here are a per-class confidence map
     * that is reduced with a per-class maximum, and duplicate anchors pointing at the same
     * body part cannot raise that maximum. Box suppression would only add latency.
     */
    private fun decodeDetections(
        values: FloatArray,
        dimensions: List<Int>,
        labels: List<String>,
    ): Map<String, Float>? {
        if (dimensions.size >= 3) {
            decodeSplitHeads(values, dimensions, labels)?.let { decoded -> return decoded }
        }
        if (dimensions.size >= 2) {
            decodeChannelFirst(values, dimensions, labels)?.let { decoded -> return decoded }
            decodeChannelLast(values, dimensions, labels)?.let { decoded -> return decoded }
        }
        return null
    }

    /** Separate box/class heads: `[1, 4|5, N]` plus a `[1, K, N]` class tensor. */
    private fun decodeSplitHeads(
        values: FloatArray,
        dimensions: List<Int>,
        labels: List<String>,
    ): Map<String, Float>? {
        if (dimensions.size != 3 || dimensions.first() != 1) {
            return null
        }
        val anchors = dimensions.last()
        val boxChannels = dimensions[1]
        if (anchors <= 0 || boxChannels !in BoxChannelCount..(BoxChannelCount + 1)) {
            return null
        }
        val classValues = decodeClassChannels(values, BoxChannelCount * anchors, anchors)
        return scoreClasses(classValues, labels, anchors)
    }

    /** `[1, 4 + K, N]` (YOLOv8) or `[1, 5 + K, N]` (YOLOv5, objectness at index 4). */
    private fun decodeChannelFirst(
        values: FloatArray,
        dimensions: List<Int>,
        labels: List<String>,
    ): Map<String, Float>? {
        val channels = dimensions[dimensions.size - 2]
        val anchors = dimensions.last()
        if (channels <= BoxChannelCount + 1 || anchors <= 1 || channels > anchors) {
            return null
        }
        if (values.size % (channels * anchors) != 0) {
            return null
        }

        val classOffset = if (hasObjectnessColumn(values, channels, anchors, labels)) 5 else 4
        if (channels - classOffset <= 0) {
            return null
        }
        DebugLog.d(context, LogTag) {
            "Decoding [$channels x $anchors] detection tensor with ${channels - classOffset} classes from column $classOffset."
        }
        val classValues = decodeClassChannels(values, classOffset * anchors, anchors)
        return scoreClasses(classValues, labels, anchors)
    }

    /** Channel-last mirrors (`[1, N, 4 + K]`, e.g. `[1, 8400, 84]` COCO) and post-NMS rows. */
    private fun decodeChannelLast(
        values: FloatArray,
        dimensions: List<Int>,
        labels: List<String>,
    ): Map<String, Float>? {
        val rows = dimensions[dimensions.size - 2]
        val columns = dimensions.last()
        if (rows <= 0 || columns <= 0 || values.size % (rows * columns) != 0) {
            return null
        }
        if (columns == PostNmsColumnCount) {
            return decodePostNmsRows(values, rows, labels)
        }
        if (columns <= BoxChannelCount + 1 || columns >= rows) {
            return null
        }

        val classOffset = if (columns == labels.size + 5) 5 else 4
        val classCount = minOf(labels.size, columns - classOffset)
        if (classCount <= 0) {
            return null
        }
        val classValues = gatherClassColumns(values, rows, columns, classOffset, classCount)
        return scoreClasses(classValues, labels.take(classCount), rows)
    }

    /** `[1, N, 6]` rows of `(x1, y1, x2, y2, score, classId)` produced by in-graph NMS. */
    private fun decodePostNmsRows(
        values: FloatArray,
        rows: Int,
        labels: List<String>,
    ): Map<String, Float>? {
        val scoreMode = resolveScoreMode(values)
        val scores = mutableMapOf<String, Float>()
        for (row in 0 until rows) {
            val base = row * PostNmsColumnCount
            val classIndex = values.getOrNull(base + PostNmsClassIndex)?.toInt() ?: continue
            if (classIndex !in labels.indices) {
                continue
            }
            val score = scoreFor(values.getOrNull(base + PostNmsScoreIndex) ?: 0f, scoreMode)
            val label = labels[classIndex]
            if (score > (scores[label] ?: 0f)) {
                scores[label] = score
            }
        }
        return scores.ifEmpty { null }
    }

    /** Copies the `[classIndex][anchor]` block of a `[channels][anchors]` tensor. */
    private fun decodeClassChannels(
        values: FloatArray,
        startIndex: Int,
        anchors: Int,
    ): FloatArray {
        val remaining = values.size - startIndex
        if (anchors <= 0 || remaining < anchors) {
            return FloatArray(0)
        }
        val classCount = minOf(remaining / anchors, MaxClassCount)
        val classValues = FloatArray(classCount * anchors)
        for (classIndex in 0 until classCount) {
            val source = startIndex + classIndex * anchors
            if (source + anchors > values.size) {
                break
            }
            values.copyInto(classValues, classIndex * anchors, source, source + anchors)
        }
        return classValues
    }

    /** Gathers `[classIndex][row]` values out of a `[rows][columns]` tensor. */
    private fun gatherClassColumns(
        values: FloatArray,
        rows: Int,
        columns: Int,
        classOffset: Int,
        classCount: Int,
    ): FloatArray {
        val classValues = FloatArray(classCount * rows)
        for (classIndex in 0 until classCount) {
            val column = classOffset + classIndex
            if (column >= columns) {
                break
            }
            for (row in 0 until rows) {
                classValues[classIndex * rows + row] = values.getOrElse(row * columns + column) { 0f }
            }
        }
        return classValues
    }

    private fun scoreClasses(
        classValues: FloatArray,
        labels: List<String>,
        anchors: Int,
    ): Map<String, Float>? {
        if (anchors <= 0 || classValues.isEmpty()) {
            return null
        }
        val classCount = minOf(labels.size, classValues.size / anchors)
        if (classCount <= 0) {
            return null
        }
        if (classCount != labels.size) {
            DebugLog.w(context, LogTag) {
                "Model emits $classCount classes but metadata lists ${labels.size} labels; surplus classes are ignored."
            }
        }

        // The logit/probability decision is made once per tensor, never per value.
        val scoreMode = resolveScoreMode(classValues)
        val scores = mutableMapOf<String, Float>()
        for (classIndex in 0 until classCount) {
            var best = 0f
            for (anchor in 0 until anchors) {
                val score = scoreFor(classValues[classIndex * anchors + anchor], scoreMode)
                if (score > best) {
                    best = score
                }
            }
            scores[labels[classIndex]] = best
        }
        return scores
    }

    /**
     * Decides whether the class values of a tensor are probabilities or raw logits.
     * Deciding per value would read a negative logit as an already small probability and
     * silently drop detections just below the unsafe threshold.
     */
    private fun resolveScoreMode(classValues: FloatArray): ScoreMode {
        if (classValues.isEmpty()) {
            return ScoreMode.UNDECIDED
        }
        val insideUnitRange = classValues.count { value ->
            value.isFinite() && value >= 0f && value <= 1f
        }
        return when {
            insideUnitRange == classValues.size -> ScoreMode.PROBABILITY
            insideUnitRange * 2 <= classValues.size -> ScoreMode.LOGIT
            else -> ScoreMode.UNDECIDED
        }
    }

    private fun scoreFor(
        rawValue: Float,
        scoreMode: ScoreMode,
    ): Float {
        if (!rawValue.isFinite()) {
            return 0f
        }
        return when (scoreMode) {
            ScoreMode.PROBABILITY -> rawValue.coerceIn(0f, 1f)
            ScoreMode.LOGIT -> stableSigmoid(rawValue)
            ScoreMode.UNDECIDED -> if (rawValue in 0f..1f) rawValue else stableSigmoid(rawValue)
        }
    }

    /** Numerically stable logistic sigmoid: no overflow for large magnitudes. */
    private fun stableSigmoid(value: Float): Float {
        if (value >= 0f) {
            return (1.0 / (1.0 + exp(-value.toDouble()))).toFloat()
        }
        val exponential = exp(value.toDouble())
        return (exponential / (1.0 + exponential)).toFloat()
    }

    /**
     * A YOLOv5 head carries an objectness column before the class columns, a YOLOv8 head
     * does not. Only treat channel 4 as objectness when the label count or the value
     * distribution agrees, otherwise every class would be shifted by one and the last
     * class dropped.
     */
    private fun hasObjectnessColumn(
        values: FloatArray,
        channels: Int,
        anchors: Int,
        labels: List<String>,
    ): Boolean {
        if (labels.isNotEmpty() && channels == labels.size + 4) {
            return false
        }
        if (labels.isNotEmpty() && channels == labels.size + 5) {
            return true
        }
        if (channels < BoxChannelCount + 2) {
            return false
        }
        val samples = sampleColumn(values, ObjectnessColumnIndex, channels, anchors)
        if (samples.isEmpty()) {
            return false
        }
        return samples.all { sample -> sample.isFinite() && sample >= 0f && sample <= 1f }
    }

    private fun sampleColumn(
        values: FloatArray,
        column: Int,
        columns: Int,
        anchors: Int,
    ): FloatArray {
        if (column < 0 || column >= columns || anchors <= 0) {
            return FloatArray(0)
        }
        val stride = (anchors / ScoreSampleLimit).coerceAtLeast(1)
        val samples = ArrayList<Float>(ScoreSampleLimit)
        var anchor = 0
        while (anchor < anchors && samples.size < ScoreSampleLimit) {
            samples.add(values[column * anchors + anchor])
            anchor += stride
        }
        return samples.toFloatArray()
    }

    /**
     * OnnxRuntime hands a float tensor back as `float[][][]` (a [FloatBuffer] for direct
     * buffers, a flat `float[]` on older runtimes), so the carrier is read recursively.
     */
    private fun flattenFloatOutput(rawOutput: Any?): FloatArray? = when (rawOutput) {
        null -> null
        is OnnxValue -> readFloatTensor(rawOutput)
        else -> flattenArrays(rawOutput, 0)
    }

    private fun readFloatTensor(value: OnnxValue): FloatArray? {
        if (value !is OnnxTensor) {
            return null
        }
        if (value.info.type != OnnxJavaType.FLOAT) {
            return null
        }
        return flattenArrays(runCatching { value.value }.getOrNull(), 0)
    }

    private fun flattenArrays(
        rawOutput: Any?,
        depth: Int,
    ): FloatArray? {
        if (rawOutput == null || depth > MaxArrayDepth) {
            return null
        }
        return when (rawOutput) {
            is FloatArray -> rawOutput
            is FloatBuffer -> {
                val duplicate = rawOutput.duplicate()
                FloatArray(duplicate.remaining()).also { flattened -> duplicate.get(flattened) }
            }
            is IntArray -> FloatArray(rawOutput.size) { index -> rawOutput[index].toFloat() }
            is Array<*> -> {
                val rows = rawOutput.map { row -> flattenArrays(row, depth + 1) ?: return null }
                val total = rows.sumOf { row -> row.size }
                FloatArray(total).also { flattened ->
                    var offset = 0
                    rows.forEach { row ->
                        row.copyInto(flattened, offset)
                        offset += row.size
                    }
                }
            }
            else -> null
        }
    }

    private fun mapScoreVector(
        values: FloatArray,
        labels: List<String>,
    ): Map<String, Float> {
        val scoreMode = resolveScoreMode(values)
        val scores = mutableMapOf<String, Float>()
        labels.forEachIndexed { index, label ->
            val rawValue = values.getOrNull(index) ?: return@forEachIndexed
            scores[label] = scoreFor(rawValue, scoreMode)
        }
        return scores
    }

    /**
     * Safe-list first. NudeNet's clothed/face/armpit/belly/feet classes contain unsafe
     * marker substrings (`FEMALE_GENITALIA_COVERED` matches "genitalia"), so matching a
     * marker without excluding the known-safe classes produces permanent false positives.
     * The unsafe result is exactly NudeNet's 7 NSFW classes.
     */
    private fun isUnsafeClassLabel(label: String): Boolean {
        val normalized = label
            .substringAfterLast(':')
            .trim()
            .trim('"', '\'', '{', '}', '[', ']', ' ')
            .uppercase()
        val knownSafe = SafeLabelPrefixes.any { prefix -> normalized.startsWith(prefix) } ||
            SafeLabelSuffixes.any { suffix -> normalized.endsWith(suffix) }
        if (knownSafe) {
            return false
        }
        return UnsafeClassMarkers.any { marker -> marker in normalized }
    }

    private companion object {
        const val DefaultUnsafeThreshold = 0.35f
        const val ConfidenceGate = 0.25f
        const val LogTag = "HaramVeilInference"
        const val ClosedMessage = "NudeNet inference engine is closed."
        const val BoxChannelCount = 4
        const val ObjectnessColumnIndex = 4
        const val PostNmsColumnCount = 6
        const val PostNmsScoreIndex = 4
        const val PostNmsClassIndex = 5
        const val MaxClassCount = 1_024
        const val MaxLabelCount = 64
        const val MaxArrayDepth = 4
        const val ScoreSampleLimit = 64

        val PreferredInputNames = listOf("images", "input")

        // NudeNet NSFW classes: BUTTOCKS_EXPOSED, FEMALE_BREAST_EXPOSED,
        // FEMALE_GENITALIA_EXPOSED, MALE_BREAST_EXPOSED, ANUS_EXPOSED,
        // MALE_GENITALIA_EXPOSED and (v3 label set) VULVA_EXPOSED.
        val UnsafeClassMarkers = setOf(
            "exposed",
            "genitalia",
            "vulva",
            "breast",
            "buttocks",
            "anus",
        )

        val SafeLabelPrefixes = setOf("FACE_", "FEET_", "ARMPITS_", "BELLY_")
        val SafeLabelSuffixes = setOf("_COVERED")

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

private enum class ScoreMode {
    PROBABILITY,
    LOGIT,
    UNDECIDED,
}
