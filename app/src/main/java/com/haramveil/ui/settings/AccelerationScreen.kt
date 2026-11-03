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

package com.haramveil.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.haramveil.data.models.AccelerationBenchmarkSummary
import com.haramveil.data.models.InferenceAccelerator
import com.haramveil.ui.HaramVeilSectionCard
import com.haramveil.ui.HaramVeilWordmarkHeader
import com.haramveil.ui.SectionLabel
import com.haramveil.ui.StatusPill

/**
 * Visual AI acceleration page: shows what the on-device hardware check decided and why,
 * lets the user pick the backend (Auto is the recommendation), and re-run the check.
 * The benchmark runs the actual bundled detector on the actual accelerators of this
 * phone, so the verdict here is exactly what Mode 3 uses at scan time.
 */
@Composable
fun AccelerationScreen(
    benchmark: AccelerationBenchmarkSummary?,
    selectedAccelerator: InferenceAccelerator,
    isBenchmarkRunning: Boolean,
    onBack: () -> Unit,
    onAcceleratorSelected: (InferenceAccelerator) -> Unit,
    onRunBenchmark: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            top = 20.dp,
            end = 20.dp,
            bottom = 120.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            HaramVeilWordmarkHeader(
                title = "Visual AI Acceleration",
                subtitle = "HaramVeil can run its image detector on your phone's AI chip. This page measures which backend is genuinely fastest on your device and keeps the choice in your hands.",
                action = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        }
        item {
            SectionLabel(text = "Current status")
        }
        item {
            StatusCard(
                benchmark = benchmark,
                selectedAccelerator = selectedAccelerator,
                isBenchmarkRunning = isBenchmarkRunning,
                onRunBenchmark = onRunBenchmark,
            )
        }
        item {
            SectionLabel(text = "Choose a backend")
        }
        item {
            BackendPickerCard(
                benchmark = benchmark,
                selectedAccelerator = selectedAccelerator,
                onAcceleratorSelected = onAcceleratorSelected,
            )
        }
        item {
            SectionLabel(text = "How this works")
        }
        item {
            ExplainerCard()
        }
    }
}

@Composable
private fun StatusCard(
    benchmark: AccelerationBenchmarkSummary?,
    selectedAccelerator: InferenceAccelerator,
    isBenchmarkRunning: Boolean,
    onRunBenchmark: () -> Unit,
) {
    val effectiveAccelerator = when (selectedAccelerator) {
        InferenceAccelerator.AUTO -> benchmark?.winner ?: InferenceAccelerator.CPU
        else -> selectedAccelerator
    }
    HaramVeilSectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (isBenchmarkRunning) {
                Text(
                    text = "Running the hardware check…",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Your phone's detector is being timed on the CPU, the optimized CPU kernels, and the AI chip. This takes a few seconds and does not capture your screen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else if (benchmark == null) {
                Text(
                    text = "Not measured yet",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Run the hardware check to find out whether your phone has a usable AI chip and whether it actually makes detection faster. Until then, Auto mode uses the standard CPU.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onRunBenchmark) {
                    Text("Run hardware check")
                }
            } else {
                val active = effectiveAccelerator == InferenceAccelerator.NNAPI ||
                    effectiveAccelerator == InferenceAccelerator.XNNPACK
                StatusPill(
                    label = if (active) {
                        "Hardware acceleration: ON — ${effectiveAccelerator.displayName}"
                    } else {
                        "Hardware acceleration: OFF — standard CPU"
                    },
                    backgroundColor = if (active) {
                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f)
                    },
                    contentColor = if (active) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    text = benchmark.verdict(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TimingRow(benchmark = benchmark)
                Text(
                    text = measuredAtCaption(benchmark),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onRunBenchmark) {
                    Text("Run hardware check again")
                }
            }
        }
    }
}

@Composable
private fun TimingRow(benchmark: AccelerationBenchmarkSummary) {
    val entries = listOf(
        "Standard CPU" to "${benchmark.cpuLatencyMs}ms",
        "Optimized CPU" to (benchmark.xnnpackLatencyMs?.let { "${it}ms" } ?: "n/a"),
        "AI chip (NNAPI)" to (benchmark.nnapiLatencyMs?.let { "${it}ms" } ?: "Not available"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        entries.forEach { (label, value) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun BackendPickerCard(
    benchmark: AccelerationBenchmarkSummary?,
    selectedAccelerator: InferenceAccelerator,
    onAcceleratorSelected: (InferenceAccelerator) -> Unit,
) {
    HaramVeilSectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InferenceAccelerator.entries.forEach { accelerator ->
                    FilterChip(
                        selected = accelerator == selectedAccelerator,
                        onClick = { onAcceleratorSelected(accelerator) },
                        label = {
                            Text(
                                text = when (accelerator) {
                                    InferenceAccelerator.AUTO -> "Auto"
                                    InferenceAccelerator.NNAPI -> "AI chip"
                                    InferenceAccelerator.XNNPACK -> "Fast CPU"
                                    InferenceAccelerator.CPU -> "Standard"
                                },
                            )
                        },
                    )
                }
            }
            Text(
                text = selectedAccelerator.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (selectedAccelerator == InferenceAccelerator.NNAPI && benchmark?.nnapiAvailable == false) {
                Text(
                    text = "Note: the hardware check found no usable AI chip on this device. This mode will simply run at standard CPU speed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ExplainerCard() {
    HaramVeilSectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ExplainerRow(
                title = "What is measured?",
                body = "The check times the exact detector your protection uses on every backend your phone offers, using a blank test frame — no screenshot of your screen is taken.",
            )
            ExplainerRow(
                title = "When is the AI chip enabled?",
                body = "Only when it is clearly worth it: at least 20% faster than the CPU and comfortably inside the scan budget. On phones without a usable AI chip, Auto mode never turns it on.",
            )
            ExplainerRow(
                title = "What if it gets slow later?",
                body = "If hardware acceleration repeatedly slows down (heat, driver problems), HaramVeil automatically finishes that session on the CPU. Nothing breaks and no detection is missed.",
            )
        }
    }
}

@Composable
private fun ExplainerRow(
    title: String,
    body: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun measuredAtCaption(benchmark: AccelerationBenchmarkSummary): String {
    val minutesAgo = (System.currentTimeMillis() - benchmark.measuredAtEpochMs) / 60_000L
    val whenText = when {
        minutesAgo < 1L -> "just now"
        minutesAgo < 60L -> "$minutesAgo min ago"
        minutesAgo < 24L * 60L -> "${minutesAgo / 60L} h ago"
        else -> "${minutesAgo / (24L * 60L)} d ago"
    }
    return buildString {
        append("Last checked ")
        append(whenText)
        if (benchmark.deviceModel.isNotBlank()) {
            append(" on ")
            append(benchmark.deviceModel)
        }
    }
}
