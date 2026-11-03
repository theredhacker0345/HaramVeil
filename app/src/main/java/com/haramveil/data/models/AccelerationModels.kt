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

package com.haramveil.data.models

/**
 * Execution backends for the Mode 3 visual model. The on-device benchmark
 * (AccelerationBenchmarkRunner) decides which one a given phone actually benefits
 * from: NNAPI on a phone without accelerator drivers is *slower* than plain CPU, so
 * hardware acceleration is enabled by measurement, never by assumption.
 */
enum class InferenceAccelerator(
    val storageValue: String,
    val displayName: String,
    val description: String,
) {
    /**
     * Use the fastest backend measured on this device. Resolves to the standard CPU
     * until a benchmark has run, so a fresh install never gets an unverified accelerator.
     */
    AUTO(
        storageValue = "auto",
        displayName = "Auto (recommended)",
        description = "Uses the fastest backend measured on this device. Falls back automatically if hardware acceleration is unavailable or slow.",
    ),
    NNAPI(
        storageValue = "nnapi",
        displayName = "Hardware (NNAPI)",
        description = "Runs the detector on the phone's AI chip (GPU/DSP/NPU) through Android's NNAPI. Fastest on supported devices; falls back to the CPU if the driver refuses the model.",
    ),
    XNNPACK(
        storageValue = "xnnpack",
        displayName = "Optimized CPU (XNNPACK)",
        description = "Runs on the CPU with Google's optimized XNNPACK kernels. A safe middle option: faster than the standard CPU on every device, no AI chip required.",
    ),
    CPU(
        storageValue = "cpu",
        displayName = "Standard CPU",
        description = "Plain ONNX Runtime CPU execution. Slowest option, but identical on every device — useful for troubleshooting.",
    ),
    ;

    companion object {
        fun fromStorageValue(value: String?): InferenceAccelerator? =
            entries.firstOrNull { it.storageValue == value }
    }
}

/** Concrete accelerator choice after AUTO resolution: the backend plus NNAPI precision. */
data class ResolvedAccelerator(
    val accelerator: InferenceAccelerator,
    val nnapiUseFp16: Boolean = false,
)

/** Outcome of the on-device acceleration benchmark, persisted in preferences. */
data class AccelerationBenchmarkSummary(
    val cpuLatencyMs: Long,
    val xnnpackLatencyMs: Long?,
    val nnapiLatencyMs: Long?,
    val nnapiUseFp16: Boolean,
    val nnapiAvailable: Boolean,
    val winner: InferenceAccelerator,
    val deviceModel: String,
    val measuredAtEpochMs: Long,
) {
    /**
     * Human-facing verdict shown on the acceleration screen; explains *why* the winner
     * won instead of just naming it.
     */
    fun verdict(): String {
        val speedup = when {
            winner == InferenceAccelerator.NNAPI && cpuLatencyMs > 0 ->
                cpuLatencyMs.toFloat() / nnapiLatencyMs!!.toFloat()
            winner == InferenceAccelerator.XNNPACK && xnnpackLatencyMs != null && cpuLatencyMs > 0 ->
                cpuLatencyMs.toFloat() / xnnpackLatencyMs.toFloat()
            else -> 1f
        }
        return when (winner) {
            InferenceAccelerator.NNAPI ->
                "This device's AI chip ran the detector ${String.format(java.util.Locale.ROOT, "%.1f", speedup)}× faster " +
                    "than the CPU (${nnapiLatencyMs}ms vs ${cpuLatencyMs}ms). " +
                    "Auto mode keeps hardware acceleration enabled."
            InferenceAccelerator.XNNPACK ->
                "No usable AI chip was found, but optimized CPU kernels ran the detector " +
                    "${String.format(java.util.Locale.ROOT, "%.1f", speedup)}× faster than the standard CPU. Auto mode uses them."
            InferenceAccelerator.CPU ->
                "Hardware acceleration did not beat the standard CPU on this device " +
                    "(${nnapiLatencyMs?.let { "${it}ms" } ?: "unavailable"} vs ${cpuLatencyMs}ms), " +
                    "so Auto mode keeps acceleration off to avoid wasting battery."
            else -> "No measurement available yet."
        }
    }
}
