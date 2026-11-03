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

import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import com.haramveil.data.models.InferenceAccelerator
import com.haramveil.data.models.ResolvedAccelerator
import java.util.EnumSet

/**
 * Builds [OrtSession.SessionOptions] for a resolved accelerator.
 *
 * NNAPI cannot execute symbolic (dynamic) input dimensions — feeding it our
 * `[batch, 3, height, width]` export unmodified would partition the graph and push
 * nearly every node back to the CPU, often slower than plain CPU execution. The
 * session-level dimension freeze below is therefore a hard prerequisite, and the names
 * must match the `dim_param` strings of the exported model ("batch"/"height"/"width").
 */
object AcceleratorSessionOptions {

    fun configure(
        sessionOptions: OrtSession.SessionOptions,
        resolved: ResolvedAccelerator,
        inputSize: Int,
    ) {
        sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
        when (resolved.accelerator) {
            InferenceAccelerator.NNAPI -> {
                sessionOptions.setSymbolicDimensionValue(SymbolicBatch, 1L)
                sessionOptions.setSymbolicDimensionValue(SymbolicHeight, inputSize.toLong())
                sessionOptions.setSymbolicDimensionValue(SymbolicWidth, inputSize.toLong())
                val flags = EnumSet.of(NNAPIFlags.CPU_DISABLED)
                if (resolved.nnapiUseFp16) {
                    flags.add(NNAPIFlags.USE_FP16)
                }
                sessionOptions.addNnapi(flags)
            }
            InferenceAccelerator.XNNPACK -> {
                // Leaving the thread-count option unset inherits ORT's intra-op count;
                // XNNPACK then shares the pool the CPU path would have used anyway.
                sessionOptions.addXnnpack(emptyMap())
            }
            InferenceAccelerator.AUTO,
            InferenceAccelerator.CPU -> Unit
        }
    }

    private const val SymbolicBatch = "batch"
    private const val SymbolicHeight = "height"
    private const val SymbolicWidth = "width"
}
