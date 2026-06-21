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

package com.haramveil

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import com.haramveil.onboarding.HaramVeilRoot
import com.haramveil.onboarding.OnboardingViewModel
import com.haramveil.ui.theme.HaramVeilTheme

class MainActivity : ComponentActivity() {

    private var onboardingViewModel: OnboardingViewModel? = null

    /**
     * Launcher for the Android file picker (Storage Access Framework).
     * Used to import the 640m ONNX model without the app needing the INTERNET permission.
     */
    private lateinit var modelImportLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register the file-picker launcher before the content view is set.
        // ONNX files don't have a registered MIME type on Android, so we accept
        // application/octet-stream (the generic binary MIME) plus the catch-all */*.
        modelImportLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? ->
            uri?.let { importedUri ->
                onboardingViewModel?.onModelFilePicked(importedUri)
            }
        }

        setContent {
            HaramVeilTheme {
                val onboardingViewModel: OnboardingViewModel = viewModel()
                this.onboardingViewModel = onboardingViewModel
                HaramVeilRoot(
                    viewModel = onboardingViewModel,
                    onImportModelRequested = {
                        modelImportLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                    },
                )
            }
        }
    }
}
