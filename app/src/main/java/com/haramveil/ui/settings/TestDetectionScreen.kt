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

import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.haramveil.data.local.StatsRepository
import com.haramveil.detection.mode1.MatchSource
import com.haramveil.detection.mode1.RiskLevel
import com.haramveil.detection.mode1.ScanResult
import com.haramveil.overlay.VeilOverlayService
import com.haramveil.ui.HaramVeilSectionCard
import com.haramveil.ui.HaramVeilWordmarkHeader
import com.haramveil.ui.SectionLabel
import com.haramveil.ui.StatusPill
import com.haramveil.utils.DetectionBus
import com.haramveil.utils.Mode1WakeRequest
import kotlinx.coroutines.launch

@Composable
fun TestDetectionScreen(
    protectionEnabled: Boolean,
    accessibilityServiceActive: Boolean,
    foregroundServiceActive: Boolean,
    lastScanHeartbeatMs: Long,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val statsRepository = remember(context) { StatsRepository.getInstance(context.applicationContext) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
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
                    title = "Test Protection",
                    subtitle = "Run quick checks to confirm HaramVeil is active on this device.",
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
                SectionLabel(text = "Readiness checks")
            }
            item {
                HaramVeilSectionCard {
                    StatusRow(
                        label = "Protection",
                        value = if (protectionEnabled) "Enabled" else "Paused",
                    )
                    StatusRow(
                        label = "Foreground service",
                        value = if (foregroundServiceActive) "Active" else "Inactive",
                    )
                    StatusRow(
                        label = "Accessibility service",
                        value = if (accessibilityServiceActive) "Active" else "Inactive",
                    )
                    StatusRow(
                        label = "Overlay permission",
                        value = if (overlayGranted) "Granted" else "Missing",
                    )
                    StatusRow(
                        label = "Last scan",
                        value = if (lastScanHeartbeatMs == 0L) {
                            "Never"
                        } else {
                            "${((System.currentTimeMillis() - lastScanHeartbeatMs) / 1000)}s ago"
                        },
                    )
                    if (!overlayGranted) {
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}"),
                                )
                                context.startActivity(intent)
                            },
                            modifier = Modifier.padding(top = 10.dp),
                        ) {
                            Text("Grant overlay permission")
                        }
                    }
                }
            }
            item {
                SectionLabel(text = "Simulation actions")
            }
            item {
                HaramVeilSectionCard {
                    Text(
                        text = "These actions simulate detections without locking apps.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            onClick = {
                                val scanResult = ScanResult(
                                    triggered = true,
                                    matchedKeyword = "test",
                                    matchSource = MatchSource.KEYWORD,
                                    packageName = context.packageName,
                                    riskLevel = RiskLevel.HIGH,
                                )
                                DetectionBus.publishMode1Triggered(
                                    Mode1WakeRequest(
                                        scanResult = scanResult,
                                        wakeMode2 = false,
                                        wakeMode3 = false,
                                    ),
                                )
                                scope.launch {
                                    snackbarHostState.showSnackbar("Mode 1 signal sent")
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Mode 1 ping")
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    statsRepository.logBlock(
                                        packageName = context.packageName,
                                        mode = 2,
                                        detail = "test_mode_2",
                                        lockdownMs = 0L,
                                        appName = "HaramVeil Test",
                                    )
                                    snackbarHostState.showSnackbar("Mode 2 test event logged")
                                }
                                DetectionBus.publishMode2Triggered(
                                    packageName = context.packageName,
                                    matchDetails = "Mode 2 test event",
                                )
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Mode 2 test")
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    statsRepository.logBlock(
                                        packageName = context.packageName,
                                        mode = 3,
                                        detail = "test_mode_3",
                                        lockdownMs = 0L,
                                        appName = "HaramVeil Test",
                                    )
                                    snackbarHostState.showSnackbar("Mode 3 test event logged")
                                }
                                DetectionBus.publishMode3Triggered(
                                    packageName = context.packageName,
                                    matchDetails = "Mode 3 test event",
                                )
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Mode 3 test")
                        }
                        Button(
                            onClick = {
                                val previewIntent = Intent(context, VeilOverlayService::class.java)
                                    .setAction(VeilOverlayService.ActionPreview)
                                runCatching {
                                    ContextCompat.startForegroundService(context, previewIntent)
                                }.onFailure { throwable ->
                                    DebugLog.w(context, "TestDetection") {
                                        "Could not start VeilOverlayService preview: ${throwable.javaClass.simpleName}"
                                    }
                                }
                                scope.launch {
                                    snackbarHostState.showSnackbar("Veil preview shown")
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Visibility,
                                contentDescription = null,
                            )
                            Text("Preview veil", modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
            item {
                HaramVeilSectionCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Tip",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Run a test event, then check Dashboard and Stats to confirm the timeline updates.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StatusPill(
            label = value,
            backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        )
    }
}
