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
import android.os.Process
import android.os.SystemClock
import com.haramveil.data.models.BenchmarkResult
import com.haramveil.data.models.ModelImportManager
import com.haramveil.data.models.ModelSource
import com.haramveil.data.models.VisualModelOption
import com.haramveil.utils.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.system.measureTimeMillis

class OnnxBenchmarkRunner(
    private val context: Context,
    private val modelImportManager: ModelImportManager =
        ModelImportManager(context.applicationContext),
) {

    suspend fun run(): BenchmarkResult =
        withContext(Dispatchers.Default) {
            // LESS_FAVORABLE keeps the thread in the foreground CPU cgroup: true
            // BACKGROUND priority confines a thread to the ~5%-CPU background group,
            // which made the benchmark read 10-50x slower than reality.
            Process.setThreadPriority(Process.THREAD_PRIORITY_LESS_FAVORABLE)

            val latency320 = benchmarkModel(VisualModelOption.MODEL_320)

            // Only benchmark 640 if the user has imported it — otherwise skip silently.
            val latency640 = if (modelImportManager.isModelInstalled(VisualModelOption.MODEL_640)) {
                benchmarkModel(VisualModelOption.MODEL_640)
            } else {
                null
            }

            val supportedModel =
                when {
                    latency640 != null && latency640 <= LatencyBudgetMs -> VisualModelOption.MODEL_640
                    latency320 != null -> VisualModelOption.MODEL_320
                    else -> null
                }

            BenchmarkResult(
                model320LatencyMs = latency320,
                model640LatencyMs = latency640,
                supportedModel = supportedModel,
                latencyBudgetMs = LatencyBudgetMs,
                errorMessage = if (latency320 == null && latency640 == null) {
                    "ONNX benchmark failed on this device."
                } else {
                    null
                },
            )
        }

    private suspend fun benchmarkModel(model: VisualModelOption): Long? {
        val modelFile = stageModelFile(model) ?: return null
        return try {
            measureLatency(model, modelFile)
        } catch (cancellation: CancellationException) {
            rethrowCancellation(cancellation)
        } catch (failure: Exception) {
            DebugLog.w(context, LogTag) {
                "ONNX benchmark failed for ${model.displayName}: ${failure.message}"
            }
            null
        } finally {
            deleteStagedFile(modelFile)
        }
    }

    /**
     * Resolves the on-disk file for [model]. Bundled assets are copied to a uniquely named
     * private cache file (`benchmark-<uuid>-…`) so a concurrent inference session using its
     * own copy is never truncated; imported files are loaded directly from filesDir/models/.
     */
    private fun stageModelFile(model: VisualModelOption): File? = try {
        when (model.source) {
            ModelSource.BUNDLED_ASSET -> {
                val assetName = model.assetName ?: error("Bundled model has no asset name")
                copyAssetToPrivateCache(assetName)
            }
            ModelSource.IMPORTED_FILE -> {
                val fileName = model.assetName ?: error("Imported model has no file name")
                File(context.filesDir, "models/$fileName").also { file ->
                    check(file.exists()) { "Imported model not found at ${file.absolutePath}" }
                }
            }
        }
    } catch (failure: Exception) {
        DebugLog.w(context, LogTag) {
            "Could not stage ${model.displayName} for benchmarking: ${failure.message}"
        }
        null
    }

    /**
     * Runs the model once on a dedicated thread and waits at most
     * [BenchmarkBudgetMs]. The wait is a real bound — a native inference run cannot
     * be interrupted, so on timeout the thread is abandoned mid-run and the session
     * is closed by the task's own finally when it eventually finishes; the caller
     * treats the model as not viable for this device and onboarding moves on.
     */
    private suspend fun measureLatency(
        model: VisualModelOption,
        modelFile: File,
    ): Long? {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "haramveil-onnx-benchmark").apply { isDaemon = true }
        }
        val startedAtMs = SystemClock.elapsedRealtime()
        return try {
            val future = executor.submit(
                Callable {
                    val environment = OrtEnvironment.getEnvironment()
                    OrtSession.SessionOptions().use { sessionOptions ->
                        environment.createSession(modelFile.absolutePath, sessionOptions).use { session ->
                            val tensorInfo = session.inputInfo.values.first().info as TensorInfo
                            val inputShape = tensorInfo.shape.mapIndexed { index, dimension ->
                                when {
                                    dimension > 0L -> dimension
                                    index == 0 -> 1L
                                    else -> model.inputSize.toLong()
                                }
                            }.toLongArray()

                            // A zero-filled buffer of the declared shape is a valid blank
                            // input; the allocate-then-recycle bitmap this used to build
                            // contributed nothing.
                            val elementCount = inputShape.fold(1L) { total, dimension -> total * dimension }
                            val inputData = FloatArray(elementCount.toInt())

                            OnnxTensor.createTensor(environment, FloatBuffer.wrap(inputData), inputShape).use { tensor ->
                                val inputName = resolveInputName(session)
                                val latency = measureTimeMillis {
                                    session.run(mapOf(inputName to tensor)).use { _ -> }
                                }
                                DebugLog.i(context, LogTag) {
                                    "ONNX benchmark: ${model.displayName} single-run ${latency}ms."
                                }
                                latency
                            }
                        }
                    }
                },
            )
            try {
                future.get(BenchmarkBudgetMs, TimeUnit.MILLISECONDS)
            } catch (timeout: TimeoutException) {
                // Leave the run to finish on its own thread (its finally closes the
                // session); the model is simply too slow to be offered for this device.
                future.cancel(false)
                DebugLog.w(context, LogTag) {
                    "ONNX benchmark: ${model.displayName} exceeded the ${BenchmarkBudgetMs / 1000}s budget — treated as unsupported."
                }
                null
            }
        } catch (failure: Exception) {
            DebugLog.w(context, LogTag) {
                "ONNX benchmark failed for ${model.displayName}: ${failure.message}"
            }
            null
        } finally {
            executor.shutdown()
            DebugLog.d(context, LogTag) {
                "ONNX benchmark: ${model.displayName} finished in ${SystemClock.elapsedRealtime() - startedAtMs}ms of wall time."
            }
        }
    }

    private fun resolveInputName(session: OrtSession): String {
        val declaredNames = session.inputInfo.keys
        return PreferredInputNames.firstOrNull { candidate -> candidate in declaredNames }
            ?: declaredNames.firstOrNull()
            ?: error("Visual model does not declare an input tensor.")
    }

    private fun copyAssetToPrivateCache(assetName: String): File? {
        val stagedFile = File(context.cacheDir, "benchmark-${UUID.randomUUID()}-$assetName")
        return try {
            context.assets.open(assetName).use { inputStream ->
                stagedFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            stagedFile
        } catch (failure: Exception) {
            stagedFile.delete()
            DebugLog.w(context, LogTag) {
                "Could not copy bundled model $assetName: ${failure.message}"
            }
            null
        }
    }

    /** Only staged copies live in cacheDir; imported models must survive the benchmark. */
    private fun deleteStagedFile(modelFile: File) {
        if (modelFile.parentFile == context.cacheDir) {
            modelFile.delete()
        }
    }

    private fun rethrowCancellation(cancellation: CancellationException): Nothing =
        throw cancellation

    private companion object {
        const val LatencyBudgetMs = 1_500L

        /**
         * Hard wall-clock budget per benchmarked model. Generous enough for every real
         * phone (the 320 model runs in well under 2s even on weak hardware), short
         * enough that onboarding can never hang for minutes on slow server CPUs or
         * starved threads.
         */
        const val BenchmarkBudgetMs = 15_000L
        const val LogTag = "HaramVeilBenchmark"

        val PreferredInputNames = listOf("images", "input")
    }
}
