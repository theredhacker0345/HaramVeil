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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.haramveil.data.models.SecurityQuestion
import com.haramveil.security.PinManager
import com.haramveil.security.SecurityQuestionsManager
import com.haramveil.ui.HaramVeilScreenBackground
import kotlinx.coroutines.launch

@Composable
fun ChangePINScreen(
    pinManager: PinManager,
    securityQuestionsManager: SecurityQuestionsManager,
    onBack: () -> Unit,
    onPinChanged: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(ChangePinStep.VERIFY_CURRENT_PIN) }
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
        active = step == ChangePinStep.VERIFY_CURRENT_PIN,
        lockedOutProvider = pinManager::isLockedOut,
        remainingMsProvider = pinManager::getLockoutRemainingMs,
    )
    val questionLockout = rememberLockoutUiState(
        active = step == ChangePinStep.SECURITY_QUESTIONS,
        lockedOutProvider = securityQuestionsManager::isLockedOut,
        remainingMsProvider = securityQuestionsManager::getLockoutRemainingMs,
    )

    HaramVeilScreenBackground {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (step) {
                ChangePinStep.VERIFY_CURRENT_PIN -> {
                    PinEntryScreen(
                        title = "Verify your current PIN",
                        subtitle = "Confirm the existing 6-digit PIN before HaramVeil saves a new one.",
                        enteredPin = pinInput,
                        confirmLabel = "Verify current PIN",
                        errorMessage = pinErrorMessage,
                        isLockedOut = pinLockout.isLockedOut,
                        lockoutRemainingMs = pinLockout.remainingMs,
                        isBusy = isBusy,
                        showForgotPin = recoveryQuestions.isNotEmpty(),
                        shakeTrigger = pinShakeTrigger,
                        cancelLabel = "Back",
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
                                            step = ChangePinStep.SET_NEW_PIN
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
                            step = ChangePinStep.SECURITY_QUESTIONS
                            pinErrorMessage = null
                        },
                        onCancel = onBack,
                    )
                }

                ChangePinStep.SECURITY_QUESTIONS -> {
                    SecurityQuestionScreen(
                        title = "Recover access with your questions",
                        subtitle = "If you forgot the current PIN, answer all 3 saved recovery questions to set a new one.",
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
                                            step = ChangePinStep.SET_NEW_PIN
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
                            step = ChangePinStep.VERIFY_CURRENT_PIN
                        },
                    )
                }

                ChangePinStep.SET_NEW_PIN -> {
                    PinEntryScreen(
                        title = "Enter your new PIN",
                        subtitle = "Choose the new 6-digit PIN you want HaramVeil to protect.",
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
                            step = ChangePinStep.CONFIRM_NEW_PIN
                        },
                        onCancel = {
                            if (isBusy) return@PinEntryScreen
                            pinInput = ""
                            pinErrorMessage = null
                            step = ChangePinStep.VERIFY_CURRENT_PIN
                        },
                    )
                }

                ChangePinStep.CONFIRM_NEW_PIN -> {
                    PinEntryScreen(
                        title = "Confirm your new PIN",
                        subtitle = "Enter the same 6 digits again to save the new bcrypt hash.",
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
                                            onPinChanged()
                                        }

                                        PinStoreResult.Unavailable -> {
                                            pinInput = ""
                                            pinErrorMessage =
                                                "The new PIN could not be saved. Please try again."
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
                            step = ChangePinStep.SET_NEW_PIN
                        },
                    )
                }
            }
        }
    }
}

private enum class ChangePinStep {
    VERIFY_CURRENT_PIN,
    SECURITY_QUESTIONS,
    SET_NEW_PIN,
    CONFIRM_NEW_PIN,
}
