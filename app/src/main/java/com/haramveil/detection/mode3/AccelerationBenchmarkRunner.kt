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
import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.haramveil.data.models.AccelerationBenchmarkSummary
import com.haramveil.data.models.InferenceAccelerator
import com.haramveil.data.models.ModelImportManager
import com.haramveil.data.models.ModelSource
import com.haramveil.data.models.VisualModelOption
import com.haramveil.utils.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import java.util.UUID
import kotlin.system.measureTimeMillis

/**
 * Measures the Mode 3 visual model against every execution backend the device offers
 * and picks a winner. Hardware acceleration is adopted only on evidence: NNAPI must
 * beat the plain CPU by [NnapiAdoptionMargin] AND stay inside the latency budget, which
 * keeps it permanently off on budget devices whose NNAPI driver is missing (where it
 * would otherwise run on a slow reference CPU path).
 */
class AccelerationBenchmarkRunner(
    private val context: Context,
    private val modelImportManager: ModelImportManager = ModelImportManager(context.applicationContext),
) {

    suspend fun run(): AccelerationBenchmarkSummary = withContext(Dispatchers.Default) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)

        val modelFile = stageModelFile()
            ?: error("Could not stage the visual model for the acceleration benchmark.")
        try {
            // The CPU path is the baseline every other backend is judged against; if even
            // that fails the device cannot run the model at all and there is no verdict.
            val cpuLatencyMs = measureProvider(
                providerName = "CPU",
                inputSize = BundledModelInputSize,
                modelFile = modelFile,
            ) { /* plain CPU */ } ?: error("The visual model failed to run on the CPU.")

            val xnnpackLatencyMs = measureProvider(
                providerName = "XNNPACK",
                inputSize = BundledModelInputSize,
                modelFile = modelFile,
            ) { sessionOptions ->
                sessionOptions.addXnnpack(emptyMap())
            }

            // NNAPI first in FP16 (the variant we would adopt); FP32 is only measured
            // when FP16 completed, so a device whose NNAPI driver is missing or hopeless
            // is not paid for twice.
            val nnapiFp16LatencyMs = measureProvider(
                providerName = "NNAPI FP16",
                inputSize = BundledModelInputSize,
                modelFile = modelFile,
            ) { sessionOptions -> configureNnapi(sessionOptions, useFp16 = true) }

            val nnapiFp32LatencyMs = if (nnapiFp16LatencyMs != null) {
                measureProvider(
                    providerName = "NNAPI FP32",
                    inputSize = BundledModelInputSize,
                    modelFile = modelFile,
                ) { sessionOptions -> configureNnapi(sessionOptions, useFp16 = false) }
            } else {
                null
            }

            val nnapiCandidate = when {
                nnapiFp16LatencyMs != null && nnapiFp32LatencyMs != null ->
                    if (nnapiFp16LatencyMs <= nnapiFp32LatencyMs) {
                        nnapiFp16LatencyMs to true
                    } else {
                        nnapiFp32LatencyMs to false
                    }
                nnapiFp16LatencyMs != null -> nnapiFp16LatencyMs to true
                nnapiFp32LatencyMs != null -> nnapiFp32LatencyMs to false
                else -> null
            }

            val winner = pickWinner(
                cpuLatencyMs = cpuLatencyMs,
                xnnpackLatencyMs = xnnpackLatencyMs,
                nnapiCandidate = nnapiCandidate,
            )

            AccelerationBenchmarkSummary(
                cpuLatencyMs = cpuLatencyMs,
                xnnpackLatencyMs = xnnpackLatencyMs,
                nnapiLatencyMs = nnapiCandidate?.first,
                nnapiUseFp16 = nnapiCandidate?.second ?: false,
                nnapiAvailable = nnapiCandidate != null,
                winner = winner,
                deviceModel = deviceModelName(),
                measuredAtEpochMs = System.currentTimeMillis(),
            )
        } finally {
            deleteStagedFile(modelFile)
        }
    }

    /**
     * NNAPI is adopted only when it is genuinely hardware-accelerated on this device:
     * measurably faster than the plain CPU path (not equal-or-noise) and comfortably
     * inside the visual-scan latency budget. XNNPACK is adopted whenever it is simply
     * not slower than the CPU — it cannot regress correctness, only speed.
     */
    private fun pickWinner(
        cpuLatencyMs: Long,
        xnnpackLatencyMs: Long?,
        nnapiCandidate: Pair<Long, Boolean>?,
    ): InferenceAccelerator {
        val nnapiEligible = nnapiCandidate != null &&
            nnapiCandidate.first <= cpuLatencyMs * NnapiAdoptionMarginNumerator / NnapiAdoptionMarginDenominator &&
            nnapiCandidate.first <= LatencyBudgetMs
        val xnnpackEligible = xnnpackLatencyMs != null && xnnpackLatencyMs < cpuLatencyMs

        return when {
            nnapiEligible && nnapiCandidate!!.first <= (xnnpackLatencyMs ?: Long.MAX_VALUE) ->
                InferenceAccelerator.NNAPI
            xnnpackEligible -> InferenceAccelerator.XNNPACK
            else -> InferenceAccelerator.CPU
        }
    }

    private fun configureNnapi(sessionOptions: OrtSession.SessionOptions, useFp16: Boolean) {
        AcceleratorSessionOptions.configure(
            sessionOptions = sessionOptions,
            resolved = com.haramveil.data.models.ResolvedAccelerator(
                accelerator = InferenceAccelerator.NNAPI,
                nnapiUseFp16 = useFp16,
            ),
            inputSize = BundledModelInputSize,
        )
    }

    /**
     * Median of the measured runs after one discarded warmup. Every provider is bound
     * by [ProviderBudgetMs] measured from before its session creation: once the budget
     * is spent, remaining warmup/timed runs are skipped and the timing of whatever
     * completed is returned. This bounds the whole check to a few seconds even on
     * devices whose NNAPI driver resolves to a very slow reference implementation —
     * a slow-but-working provider keeps its (bad) timing and loses the adoption rule,
     * instead of stalling the check.
     */
    private suspend fun measureProvider(
        providerName: String,
        inputSize: Int,
        modelFile: File,
        configure: (OrtSession.SessionOptions) -> Unit,
    ): Long? {
        val environment = OrtEnvironment.getEnvironment()
        val providerStartedAtMs = SystemClock.elapsedRealtime()
        fun overBudget(): Boolean =
            SystemClock.elapsedRealtime() - providerStartedAtMs > ProviderBudgetMs

        return try {
            OrtSession.SessionOptions().use { sessionOptions ->
                configure(sessionOptions)
                environment.createSession(modelFile.absolutePath, sessionOptions).use { session ->
                    val inputShape = resolveInputShape(session, inputSize)
                    val elementCount = inputShape.fold(1L) { total, dimension -> total * dimension }
                    OnnxTensor.createTensor(
                        environment,
                        FloatBuffer.wrap(FloatArray(elementCount.toInt())),
                        inputShape,
                    ).use { tensor ->
                        val inputName = resolveInputName(session)
                        if (overBudget()) {
                            DebugLog.w(context, LogTag) {
                                "Acceleration benchmark: $providerName exceeded its ${ProviderBudgetMs}ms budget during setup."
                            }
                            return null
                        }
                        currentCoroutineContext().ensureActive()
                        session.run(mapOf(inputName to tensor)).use { }
                        if (overBudget()) {
                            return null
                        }
                        val timings = mutableListOf<Long>()
                        var run = 0
                        while (run < TimingRuns && !overBudget()) {
                            currentCoroutineContext().ensureActive()
                            timings += measureTimeMillis {
                                session.run(mapOf(inputName to tensor)).use { }
                            }
                            run += 1
                        }
                        if (timings.isEmpty()) {
                            return null
                        }
                        timings.sorted()[timings.size / 2]
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            DebugLog.d(context, LogTag) {
                "Acceleration benchmark: $providerName unavailable or failed (${failure.javaClass.simpleName})."
            }
            null
        }
    }

    private fun resolveInputShape(session: OrtSession, inputSize: Int): LongArray =
        (session.inputInfo.values.firstOrNull()?.info as? TensorInfo)?.shape?.mapIndexed { index, dimension ->
            when {
                dimension > 0L -> dimension
                index == 0 -> 1L
                else -> inputSize.toLong()
            }
        }?.toLongArray() ?: longArrayOf(1L, 3L, inputSize.toLong(), inputSize.toLong())

    private fun resolveInputName(session: OrtSession): String {
        val declaredNames = session.inputInfo.keys
        return PreferredInputNames.firstOrNull { candidate -> candidate in declaredNames }
            ?: declaredNames.firstOrNull()
            ?: error("Visual model does not declare an input tensor.")
    }

    private fun deviceModelName(): String =
        listOf(Build.MANUFACTURER, Build.MODEL)
            .joinToString(" ")
            .trim()
            .ifEmpty { "unknown device" }

    private fun stageModelFile(): File? = try {
        when (BundledModel.source) {
            ModelSource.BUNDLED_ASSET -> stageBundledAsset()
            ModelSource.IMPORTED_FILE -> stageImportedModel()
        }
    } catch (failure: Exception) {
        DebugLog.w(context, LogTag) {
            "Could not stage the visual model for the acceleration benchmark: ${failure.message}"
        }
        null
    }

    private fun stageBundledAsset(): File {
        val assetName = BundledModel.assetName
            ?: error("Bundled model ${BundledModel.displayName} has no asset name")
        val stagedFile = File(context.cacheDir, "acceleration-${UUID.randomUUID()}-$assetName")
        try {
            context.assets.open(assetName).use { inputStream ->
                stagedFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            return stagedFile
        } catch (failure: Exception) {
            stagedFile.delete()
            throw failure
        }
    }

    private fun stageImportedModel(): File {
        val fileName = BundledModel.assetName ?: error("Imported model has no file name")
        return File(context.filesDir, "models/$fileName").also { file ->
            check(file.exists()) { "Imported model not found at ${file.absolutePath}" }
        }
    }

    /** Only staged copies live in cacheDir; imported models must survive the benchmark. */
    private fun deleteStagedFile(modelFile: File) {
        if (modelFile.parentFile == context.cacheDir) {
            modelFile.delete()
        }
    }

    private companion object {
        const val LogTag = "HaramVeilAcceleration"
        const val TimingRuns = 5

        /**
         * Per-provider wall-clock budget covering session creation, one warmup run and
         * the timed runs. Caps the whole 4-provider check at roughly 4x this even on
         * devices with pathologically slow backends (e.g. the emulator's
         * nnapi-reference driver).
         */
        const val ProviderBudgetMs = 2_500L

        // NNAPI must be at least 20% faster than the plain CPU to be worth the
        // partitioning overhead and driver risk on any given device.
        const val NnapiAdoptionMarginNumerator = 4L
        const val NnapiAdoptionMarginDenominator = 5L
        const val LatencyBudgetMs = 1_500L

        val BundledModel = VisualModelOption.MODEL_320
        val BundledModelInputSize = BundledModel.inputSize

        val PreferredInputNames = listOf("images", "input")
    }
}
