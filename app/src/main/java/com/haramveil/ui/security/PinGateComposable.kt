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

package com.haramveil.ui.security

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.haramveil.data.models.SecurityQuestion
import com.haramveil.security.PinManager
import com.haramveil.security.SecurityQuestionsManager
import com.haramveil.ui.HaramVeilScreenBackground
import com.haramveil.utils.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PinGateComposable(
    authenticated: Boolean,
    pinManager: PinManager,
    securityQuestionsManager: SecurityQuestionsManager,
    title: String,
    subtitle: String,
    onAuthenticated: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (authenticated || !pinManager.isSet()) {
        content()
        return
    }

    HaramVeilScreenBackground {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            AuthenticationAndRecoveryPanel(
                pinManager = pinManager,
                securityQuestionsManager = securityQuestionsManager,
                title = title,
                subtitle = subtitle,
                onAuthenticated = onAuthenticated,
                onCancel = null,
            )
        }
    }
}

@Composable
fun PinActionGateDialog(
    visible: Boolean,
    pinManager: PinManager,
    securityQuestionsManager: SecurityQuestionsManager,
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
    onAuthenticated: () -> Unit,
) {
    if (!visible) {
        return
    }

    if (!pinManager.isSet()) {
        LaunchedEffect(visible) {
            onAuthenticated()
        }
        return
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(30.dp),
            color = MaterialTheme.colorScheme.background,
        ) {
            AuthenticationAndRecoveryPanel(
                pinManager = pinManager,
                securityQuestionsManager = securityQuestionsManager,
                title = title,
                subtitle = subtitle,
                onAuthenticated = onAuthenticated,
                onCancel = onDismiss,
            )
        }
    }
}

private const val SecurityLogTag = "HaramVeilSecurityUi"
private const val LockoutCountdownTickMs = 1_000L

@Composable
private fun AuthenticationAndRecoveryPanel(
    pinManager: PinManager,
    securityQuestionsManager: SecurityQuestionsManager,
    title: String,
    subtitle: String,
    onAuthenticated: () -> Unit,
    onCancel: (() -> Unit)?,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(AuthenticationStep.ENTER_PIN) }
    var pinInput by remember { mutableStateOf("") }
    var stagedNewPin by remember { mutableStateOf("") }
    var pinErrorMessage by remember { mutableStateOf<String?>(null) }
    var questionErrorMessage by remember { mutableStateOf<String?>(null) }
    var pinShakeTrigger by remember { mutableIntStateOf(0) }
    var isBusy by remember { mutableStateOf(false) }
    val recoveryAnswers = remember { mutableStateMapOf<Int, Boolean>() }
    val recoveryQuestions by produceState(initialValue = emptyList<SecurityQuestion>()) {
        value = runCatching { securityQuestionsManager.configuredQuestions() }
            .getOrDefault(emptyList())
    }
    val pinLockout = rememberLockoutUiState(
        active = step == AuthenticationStep.ENTER_PIN,
        lockedOutProvider = pinManager::isLockedOut,
        remainingMsProvider = pinManager::getLockoutRemainingMs,
    )
    val questionLockout = rememberLockoutUiState(
        active = step == AuthenticationStep.SECURITY_QUESTIONS,
        lockedOutProvider = securityQuestionsManager::isLockedOut,
        remainingMsProvider = securityQuestionsManager::getLockoutRemainingMs,
    )

    when (step) {
        AuthenticationStep.ENTER_PIN -> {
            PinEntryScreen(
                title = title,
                subtitle = subtitle,
                enteredPin = pinInput,
                confirmLabel = "Unlock",
                errorMessage = pinErrorMessage,
                isLockedOut = pinLockout.isLockedOut,
                lockoutRemainingMs = pinLockout.remainingMs,
                isBusy = isBusy,
                showForgotPin = recoveryQuestions.isNotEmpty(),
                shakeTrigger = pinShakeTrigger,
                cancelLabel = if (onCancel != null) "Cancel" else null,
                onDigitPressed = { digit ->
                    if (isBusy || pinLockout.isLockedOut) return@PinEntryScreen
                    pinErrorMessage = null
                    if (pinInput.length < RequiredPinLength) {
                        pinInput += digit
                    }
                },
                onBackspace = {
                    if (isBusy || pinLockout.isLockedOut) return@PinEntryScreen
                    pinErrorMessage = null
                    if (pinInput.isNotEmpty()) {
                        pinInput = pinInput.dropLast(1)
                    }
                },
                onClear = {
                    if (isBusy || pinLockout.isLockedOut) return@PinEntryScreen
                    pinErrorMessage = null
                    pinInput = ""
                },
                onConfirm = {
                    if (isBusy || pinLockout.isLockedOut) return@PinEntryScreen
                    val attempt = pinInput
                    isBusy = true
                    pinErrorMessage = null
                    scope.launch {
                        try {
                            when (val result = verifyPinOffMainThread(context, pinManager, attempt)) {
                                is PinCheckResult.Verified -> {
                                    pinLockout.updateFromIo(isLockedOut = false, remainingMs = 0L)
                                    pinInput = ""
                                    pinErrorMessage = null
                                    onAuthenticated()
                                }

                                is PinCheckResult.Rejected -> {
                                    pinInput = ""
                                    pinShakeTrigger += 1
                                    pinLockout.updateFromIo(
                                        isLockedOut = result.isLockedOut,
                                        remainingMs = result.remainingMs,
                                    )
                                    pinErrorMessage = if (result.isLockedOut) {
                                        "Too many wrong PIN attempts. Try again in ${formatLockoutCountdown(result.remainingMs)}."
                                    } else {
                                        "Incorrect PIN. ${result.attemptsLeft} attempts left."
                                    }
                                }

                                PinCheckResult.Unavailable -> {
                                    pinInput = ""
                                    pinErrorMessage = "PIN check failed. Please try again."
                                }
                            }
                        } finally {
                            isBusy = false
                        }
                    }
                },
                onForgotPin = {
                    if (isBusy) return@PinEntryScreen
                    step = AuthenticationStep.SECURITY_QUESTIONS
                    pinErrorMessage = null
                },
                onCancel = onCancel,
            )
        }

        AuthenticationStep.SECURITY_QUESTIONS -> {
            SecurityQuestionScreen(
                title = "Answer your recovery questions",
                subtitle = "All 3 answers must match. Three wrong tries trigger a 20-minute lockout.",
                questions = recoveryQuestions,
                selectedAnswers = recoveryAnswers,
                errorMessage = questionErrorMessage,
                isLockedOut = questionLockout.isLockedOut,
                lockoutRemainingMs = questionLockout.remainingMs,
                isBusy = isBusy,
                onAnswerSelected = { index, answer ->
                    if (isBusy || questionLockout.isLockedOut) {
                        return@SecurityQuestionScreen
                    }
                    questionErrorMessage = null
                    recoveryAnswers[index] = answer
                },
                onSubmit = {
                    if (isBusy || questionLockout.isLockedOut) return@SecurityQuestionScreen
                    val attempts = recoveryAnswers.toMap()
                    isBusy = true
                    questionErrorMessage = null
                    scope.launch {
                        try {
                            when (
                                val result = verifyRecoveryAnswersOffMainThread(
                                    context = context,
                                    securityQuestionsManager = securityQuestionsManager,
                                    answers = attempts,
                                )
                            ) {
                                is RecoveryCheckResult.Verified -> {
                                    questionLockout.updateFromIo(isLockedOut = false, remainingMs = 0L)
                                    pinInput = ""
                                    stagedNewPin = ""
                                    step = AuthenticationStep.SET_NEW_PIN
                                }

                                is RecoveryCheckResult.Rejected -> {
                                    questionLockout.updateFromIo(
                                        isLockedOut = result.isLockedOut,
                                        remainingMs = result.remainingMs,
                                    )
                                    questionErrorMessage = if (result.isLockedOut) {
                                        "Recovery is locked. Try again in ${formatLockoutCountdown(result.remainingMs)}."
                                    } else {
                                        "Those answers did not match. ${result.attemptsLeft} attempts left."
                                    }
                                }

                                RecoveryCheckResult.Unavailable -> {
                                    questionErrorMessage = "Recovery check failed. Please try again."
                                }
                            }
                        } finally {
                            isBusy = false
                        }
                    }
                },
                onBack = {
                    if (isBusy) return@SecurityQuestionScreen
                    questionErrorMessage = null
                    recoveryAnswers.clear()
                    step = AuthenticationStep.ENTER_PIN
                },
            )
        }

        AuthenticationStep.SET_NEW_PIN -> {
            PinEntryScreen(
                title = "Set a new PIN",
                subtitle = "Choose a new 6-digit PIN. The bcrypt hash stays encrypted on-device and survives app updates as long as the key alias stays the same.",
                enteredPin = pinInput,
                confirmLabel = "Continue",
                errorMessage = pinErrorMessage,
                isBusy = isBusy,
                shakeTrigger = pinShakeTrigger,
                cancelLabel = "Back",
                onDigitPressed = { digit ->
                    if (isBusy) return@PinEntryScreen
                    pinErrorMessage = null
                    if (pinInput.length < RequiredPinLength) {
                        pinInput += digit
                    }
                },
                onBackspace = {
                    if (isBusy) return@PinEntryScreen
                    pinErrorMessage = null
                    if (pinInput.isNotEmpty()) {
                        pinInput = pinInput.dropLast(1)
                    }
                },
                onClear = {
                    if (isBusy) return@PinEntryScreen
                    pinErrorMessage = null
                    pinInput = ""
                },
                onConfirm = {
                    if (isBusy || pinInput.length != RequiredPinLength) return@PinEntryScreen
                    stagedNewPin = pinInput
                    pinInput = ""
                    pinErrorMessage = null
                    step = AuthenticationStep.CONFIRM_NEW_PIN
                },
                onCancel = {
                    if (isBusy) return@PinEntryScreen
                    pinInput = ""
                    pinErrorMessage = null
                    step = AuthenticationStep.SECURITY_QUESTIONS
                },
            )
        }

        AuthenticationStep.CONFIRM_NEW_PIN -> {
            PinEntryScreen(
                title = "Confirm your new PIN",
                subtitle = "Enter the same 6 digits again to finish the reset.",
                enteredPin = pinInput,
                confirmLabel = "Save new PIN",
                errorMessage = pinErrorMessage,
                isBusy = isBusy,
                shakeTrigger = pinShakeTrigger,
                cancelLabel = "Back",
                onDigitPressed = { digit ->
                    if (isBusy) return@PinEntryScreen
                    pinErrorMessage = null
                    if (pinInput.length < RequiredPinLength) {
                        pinInput += digit
                    }
                },
                onBackspace = {
                    if (isBusy) return@PinEntryScreen
                    pinErrorMessage = null
                    if (pinInput.isNotEmpty()) {
                        pinInput = pinInput.dropLast(1)
                    }
                },
                onClear = {
                    if (isBusy) return@PinEntryScreen
                    pinErrorMessage = null
                    pinInput = ""
                },
                onConfirm = {
                    if (isBusy) return@PinEntryScreen
                    if (pinInput.length != RequiredPinLength || pinInput != stagedNewPin) {
                        pinInput = ""
                        pinShakeTrigger += 1
                        pinErrorMessage = "The two PIN entries did not match. Please try again."
                        return@PinEntryScreen
                    }
                    val newPin = pinInput
                    isBusy = true
                    pinErrorMessage = null
                    scope.launch {
                        try {
                            when (storePinOffMainThread(context, pinManager, newPin)) {
                                is PinStoreResult.Stored -> {
                                    pinInput = ""
                                    pinErrorMessage = null
                                    onAuthenticated()
                                }

                                PinStoreResult.Unavailable -> {
                                    pinInput = ""
                                    pinErrorMessage = "The new PIN could not be saved. Please try again."
                                }
                            }
                        } finally {
                            isBusy = false
                        }
                    }
                },
                onCancel = {
                    if (isBusy) return@PinEntryScreen
                    pinInput = ""
                    pinErrorMessage = null
                    step = AuthenticationStep.SET_NEW_PIN
                },
            )
        }
    }
}

/**
 * Lockout snapshot for the PIN gate screens.
 *
 * Reading lockout state touches encrypted preferences and a [android.provider.Settings.Global]
 * BOOT_COUNT query, so every value here is written from a [Dispatchers.IO] coroutine and never
 * during composition.
 */
internal class LockoutUiState {
    var isLockedOut by mutableStateOf(false)
        private set

    var remainingMs by mutableLongStateOf(0L)
        private set

    fun updateLockedOut(isLockedOut: Boolean, remainingMs: Long) {
        this.isLockedOut = isLockedOut
        this.remainingMs = if (isLockedOut) remainingMs.coerceAtLeast(0L) else 0L
    }

    fun updateRemaining(remainingMs: Long) {
        this.remainingMs = remainingMs.coerceAtLeast(0L)
    }

    fun clear() {
        updateLockedOut(isLockedOut = false, remainingMs = 0L)
    }
}

/**
 * Snapshot of a lockout that is refreshed on [Dispatchers.IO] instead of during composition.
 *
 * The countdown effect is keyed on the locked-out transition, so it restarts only when that
 * transition flips and can never re-trigger itself.
 */
@Composable
internal fun rememberLockoutUiState(
    active: Boolean,
    lockedOutProvider: () -> Boolean,
    remainingMsProvider: () -> Long,
): LockoutUiState {
    val lockoutUiState = remember { LockoutUiState() }

    LaunchedEffect(lockoutUiState, active) {
        withContext(Dispatchers.IO) {
            if (!active) {
                lockoutUiState.clear()
                return@withContext
            }
            lockoutUiState.updateLockedOut(
                isLockedOut = readLockedOut(lockedOutProvider = lockedOutProvider),
                remainingMs = readRemainingMs(remainingMsProvider = remainingMsProvider, fallbackMs = 0L),
            )
        }
    }

    LaunchedEffect(lockoutUiState, lockoutUiState.isLockedOut) {
        withContext(Dispatchers.IO) {
            while (lockoutUiState.isLockedOut) {
                delay(LockoutCountdownTickMs)
                val remainingMs = readRemainingMs(
                    remainingMsProvider = remainingMsProvider,
                    fallbackMs = lockoutUiState.remainingMs,
                )
                if (remainingMs <= 0L) {
                    lockoutUiState.clear()
                } else {
                    lockoutUiState.updateRemaining(remainingMs)
                }
            }
        }
    }

    return lockoutUiState
}

internal sealed interface PinCheckResult {
    data object Verified : PinCheckResult

    data class Rejected(
        val isLockedOut: Boolean,
        val remainingMs: Long,
        val attemptsLeft: Int,
    ) : PinCheckResult

    data object Unavailable : PinCheckResult
}

internal sealed interface PinStoreResult {
    data object Stored : PinStoreResult

    data object Unavailable : PinStoreResult
}

internal sealed interface RecoveryCheckResult {
    data object Verified : RecoveryCheckResult

    data class Rejected(
        val isLockedOut: Boolean,
        val remainingMs: Long,
        val attemptsLeft: Int,
    ) : RecoveryCheckResult

    data object Unavailable : RecoveryCheckResult
}

internal suspend fun LockoutUiState.updateFromIo(
    isLockedOut: Boolean,
    remainingMs: Long,
) {
    withContext(Dispatchers.IO) {
        updateLockedOut(isLockedOut = isLockedOut, remainingMs = remainingMs)
    }
}

/**
 * Verifies a PIN off the main thread: bcrypt runs on [Dispatchers.Default] and every lockout
 * counter read or write runs on [Dispatchers.IO].
 */
internal suspend fun verifyPinOffMainThread(
    context: Context,
    pinManager: PinManager,
    attempt: String,
): PinCheckResult =
    try {
        if (pinManager.verifyPINAsync(attempt)) {
            withContext(Dispatchers.IO) {
                pinManager.resetFailedAttempts()
            }
            PinCheckResult.Verified
        } else {
            withContext(Dispatchers.IO) {
                pinManager.onWrongAttempt()
                PinCheckResult.Rejected(
                    isLockedOut = pinManager.isLockedOut(),
                    remainingMs = pinManager.getLockoutRemainingMs(),
                    attemptsLeft = pinManager.remainingAttemptsBeforeLockout(),
                )
            }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        DebugLog.w(context, SecurityLogTag) {
            "PIN verification failed: ${throwable.javaClass.simpleName}: ${throwable.message}"
        }
        PinCheckResult.Unavailable
    }

/** Stores a new PIN off the main thread and never lets a rejection escape as a crash. */
internal suspend fun storePinOffMainThread(
    context: Context,
    pinManager: PinManager,
    pin: String,
): PinStoreResult =
    try {
        pinManager.storePINAsync(pin)
        PinStoreResult.Stored
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        DebugLog.w(context, SecurityLogTag) {
            "PIN storage failed: ${throwable.javaClass.simpleName}: ${throwable.message}"
        }
        PinStoreResult.Unavailable
    }

/** Verifies recovery answers off the main thread so lockout counters never block the UI thread. */
internal suspend fun verifyRecoveryAnswersOffMainThread(
    context: Context,
    securityQuestionsManager: SecurityQuestionsManager,
    answers: Map<Int, Boolean>,
): RecoveryCheckResult =
    withContext(Dispatchers.IO) {
        try {
            if (securityQuestionsManager.verifyAnswers(answers)) {
                securityQuestionsManager.resetFailedAttempts()
                RecoveryCheckResult.Verified
            } else {
                securityQuestionsManager.onWrongAttempt()
                RecoveryCheckResult.Rejected(
                    isLockedOut = securityQuestionsManager.isLockedOut(),
                    remainingMs = securityQuestionsManager.getLockoutRemainingMs(),
                    attemptsLeft = securityQuestionsManager.remainingAttemptsBeforeLockout(),
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            DebugLog.w(context, SecurityLogTag) {
                "Recovery verification failed: ${throwable.javaClass.simpleName}: ${throwable.message}"
            }
            RecoveryCheckResult.Unavailable
        }
    }

private fun readLockedOut(lockedOutProvider: () -> Boolean): Boolean =
    runCatching { lockedOutProvider() }
        .getOrElse { throwable ->
            Log.w(
                SecurityLogTag,
                "Lockout check failed: ${throwable.javaClass.simpleName}: ${throwable.message}",
            )
            false
        }

private fun readRemainingMs(
    remainingMsProvider: () -> Long,
    fallbackMs: Long,
): Long =
    runCatching { remainingMsProvider() }
        .getOrElse { throwable ->
            Log.w(
                SecurityLogTag,
                "Lockout countdown read failed: ${throwable.javaClass.simpleName}: ${throwable.message}",
            )
            fallbackMs
        }

private enum class AuthenticationStep {
    ENTER_PIN,
    SECURITY_QUESTIONS,
    SET_NEW_PIN,
    CONFIRM_NEW_PIN,
}
