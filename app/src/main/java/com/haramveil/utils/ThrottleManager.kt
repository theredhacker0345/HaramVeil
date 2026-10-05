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

package com.haramveil.utils

import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class PendingAccessibilityEvent(
    val packageName: String,
    val eventType: Int,
)

class ThrottledScanTrigger(
    val packageName: String,
    val eventType: Int,
    val nodeHash: Int,
    val rootNode: AccessibilityNodeInfo,
    val isBrowser: Boolean = false,
    val isHighRisk: Boolean = false,
    val issuedAtEpochMs: Long = System.currentTimeMillis(),
) {
    private val isRecycled = AtomicBoolean(false)

    fun recycle() {
        if (isRecycled.compareAndSet(false, true)) {
            runCatching { rootNode.recycle() }
        }
    }
}

class ThrottleManager(
    scope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider = DispatcherProvider(),
    initialDebounceMs: Long = 500L,
    private val buildTrigger: suspend (PendingAccessibilityEvent) -> ThrottledScanTrigger?,
) {
    private val debounceMs = MutableStateFlow(initialDebounceMs.coerceIn(minimumValue = 250L, maximumValue = 2_000L))
    // Single-slot conflate: only the newest event of a burst survives until the debounce elapses.
    private val pendingSlot = AtomicReference<PendingAccessibilityEvent?>(null)
    private val wakeSignals = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val _scanTriggers = MutableSharedFlow<ThrottledScanTrigger>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    private val unconsumedTrigger = AtomicReference<ThrottledScanTrigger?>(null)
    private val stopped = AtomicBoolean(false)
    private var lastProcessedFingerprint: Pair<String, Int>? = null

    val scanTriggers = _scanTriggers.asSharedFlow()

    private val collectorJob: Job = scope.launch(dispatcherProvider.default) {
        wakeSignals.collect {
            delay(debounceMs.value)
            val pendingEvent = pendingSlot.getAndSet(null) ?: return@collect
            val trigger = runCatching { buildTrigger(pendingEvent) }.getOrNull() ?: return@collect
            unconsumedTrigger.set(trigger)

            val fingerprint = pendingEvent.packageName to trigger.nodeHash
            if (fingerprint == lastProcessedFingerprint) {
                unconsumedTrigger.compareAndSet(trigger, null)
                trigger.recycle()
                return@collect
            }

            lastProcessedFingerprint = fingerprint
            val delivered = try {
                _scanTriggers.emit(trigger)
                true
            } catch (cancellation: CancellationException) {
                trigger.recycle()
                throw cancellation
            } catch (_: Throwable) {
                false
            }
            if (delivered) {
                unconsumedTrigger.compareAndSet(trigger, null)
            } else {
                trigger.recycle()
            }
        }
    }

    init {
        collectorJob.invokeOnCompletion { drainPendingTriggers() }
    }

    fun submit(
        packageName: String,
        eventType: Int,
    ) {
        if (stopped.get()) {
            return
        }
        pendingSlot.set(
            PendingAccessibilityEvent(
                packageName = packageName,
                eventType = eventType,
            ),
        )
        wakeSignals.tryEmit(Unit)
    }

    fun updateDebounceMs(intervalMs: Long) {
        debounceMs.value = intervalMs.coerceIn(minimumValue = 250L, maximumValue = 2_000L)
    }

    fun stop() {
        stopped.set(true)
        collectorJob.cancel()
        drainPendingTriggers()
    }

    private fun drainPendingTriggers() {
        pendingSlot.set(null)
        while (true) {
            val trigger = unconsumedTrigger.getAndSet(null) ?: break
            trigger.recycle()
        }
    }
}
