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

import android.content.Context
import com.haramveil.data.local.OnboardingPreferencesRepository
import com.haramveil.data.local.ProtectionPreferencesRepository
import com.haramveil.data.models.ModelImportManager
import com.haramveil.data.models.ModelSource
import com.haramveil.data.models.VisualModelOption

data class ModelConfig(
    val visualModel: VisualModelOption,
    val assetPath: String,
    val inputSize: Int,
)

data class ModelSelectionState(
    val modelConfig: ModelConfig,
    val supports640Model: Boolean,
    val installedModels: Set<VisualModelOption>,
)

class ModelSelector(
    context: Context,
    private val protectionPreferencesRepository: ProtectionPreferencesRepository =
        ProtectionPreferencesRepository(context.applicationContext),
    private val onboardingPreferencesRepository: OnboardingPreferencesRepository =
        OnboardingPreferencesRepository(context.applicationContext),
    private val modelImportManager: ModelImportManager =
        ModelImportManager(context.applicationContext),
) {

    suspend fun select(): ModelConfig = readSelectionState().modelConfig

    suspend fun readSelectionState(): ModelSelectionState {
        val protectionSettings = protectionPreferencesRepository.readSettings()
        val onboardingSnapshot = onboardingPreferencesRepository.readSnapshot()
        val supportedModel = onboardingSnapshot.benchmarkResult?.supportedModel
        val preferredModel = protectionSettings.selectedVisualModel ?: onboardingSnapshot.selectedVisualModel

        val installedModels = VisualModelOption.entries
            .filter { modelImportManager.isModelInstalled(it) }
            .toSet()

        val resolvedModel = when {
            // User picked 640 AND it's actually installed → use it
            preferredModel == VisualModelOption.MODEL_640 &&
                VisualModelOption.MODEL_640 in installedModels -> VisualModelOption.MODEL_640

            // User picked custom AND it's installed → use it
            preferredModel == VisualModelOption.MODEL_CUSTOM &&
                VisualModelOption.MODEL_CUSTOM in installedModels -> VisualModelOption.MODEL_CUSTOM

            // Fallback: always have 320 (bundled)
            else -> VisualModelOption.MODEL_320
        }

        return ModelSelectionState(
            modelConfig = ModelConfig(
                visualModel = resolvedModel,
                assetPath = resolvedModel.assetName.orEmpty(),
                inputSize = resolvedModel.inputSize,
            ),
            supports640Model = VisualModelOption.MODEL_640 in installedModels,
            installedModels = installedModels,
        )
    }
}
