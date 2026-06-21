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

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.haramveil.utils.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages importing ONNX model files (e.g. the 640m "Enhanced" NudeNet model or a
 * custom user-supplied model) from the Android file picker into the app's private
 * storage.
 *
 * HaramVeil ships without the INTERNET permission in release builds, so the app
 * cannot download model files itself. Instead, the user downloads the `.onnx`
 * file with their own browser and then hands it to HaramVeil via the system file
 * picker (Storage Access Framework). This class handles the copy + validation.
 */
class ModelImportManager(
    private val context: Context,
) {

    /**
     * Copies the ONNX file at [sourceUri] into the app's private files dir using the
     * target [model]'s expected filename (e.g. `640m.onnx`).
     *
     * Validates that the file is a real ONNX model (magic bytes `0x08 0x05` at offset 0,
     * which is the protobuf field tag for the `ir_version` field in the ModelProto).
     *
     * @return [ImportResult.Success] with the absolute path if the file was copied and
     *   validated, or [ImportResult.Failure] with a human-readable reason.
     */
    suspend fun importModel(
        sourceUri: Uri,
        model: VisualModelOption,
    ): ImportResult = withContext(Dispatchers.IO) {
        val targetFileName = model.assetName ?: return@withContext ImportResult.Failure(
            "This model does not use an imported file.",
        )
        val targetFile = File(context.filesDir, "models/$targetFileName")

        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                targetFile.parentFile?.mkdirs()
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext ImportResult.Failure(
                "Could not open the selected file. Try a different file manager.",
            )
        } catch (e: Exception) {
            DebugLog.w(context, "ModelImportManager") { "Copy failed: ${e.message}" }
            return@withContext ImportResult.Failure(
                "Failed to read the file: ${e.message ?: "unknown error"}",
            )
        }

        when (val validation = validateOnnxFile(targetFile)) {
            is ValidationOutcome.Valid -> ImportResult.Success(targetFile.absolutePath)
            is ValidationOutcome.Invalid -> {
                targetFile.delete()
                ImportResult.Failure(validation.reason)
            }
        }
    }

    /**
     * Returns `true` if the given [model] is already imported into private storage.
     * For [ModelSource.BUNDLED_ASSET] models this is always `true` (they ship in the APK).
     */
    fun isModelInstalled(model: VisualModelOption): Boolean = when (model.source) {
        ModelSource.BUNDLED_ASSET -> true
        ModelSource.IMPORTED_FILE -> {
            val name = model.assetName ?: return false
            File(context.filesDir, "models/$name").exists()
        }
    }

    /**
     * Deletes an imported model file from private storage. No-op for bundled assets.
     */
    fun removeImportedModel(model: VisualModelOption): Boolean {
        if (model.source != ModelSource.IMPORTED_FILE) return false
        val name = model.assetName ?: return false
        return File(context.filesDir, "models/$name").delete()
    }

    /**
     * Builds an [Intent] that opens the user's browser at the NudeNet v3.4 weights
     * release page, so they can download `640m.onnx` themselves.
     *
     * This works without the INTERNET permission because the browser (not HaramVeil)
     * performs the download.
     */
    fun buildDownloadInstructionsIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL_640M))

    private fun validateOnnxFile(file: File): ValidationOutcome {
        if (!file.exists() || file.length() < MIN_ONNX_SIZE_BYTES) {
            return ValidationOutcome.Invalid(
                "This file is too small to be a valid ONNX model (minimum $MIN_ONNX_SIZE_BYTES bytes).",
            )
        }

        // Check ONNX/protobuf magic. ONNX files are protobuf streams; the first byte
        // of a ModelProto is typically 0x08 (field 1, wire type varint) followed by
        // the IR version. IR versions are currently 1-10 (2026), but we accept up to
        // 0x7F to avoid rejecting future versions (protobuf varint encoding allows this).
        try {
            file.inputStream().use { stream ->
                val first = stream.read()
                val second = stream.read()
                if (first == 0x08 && second in 0x01..0x7F) {
                    return ValidationOutcome.Valid
                }
            }
        } catch (e: Exception) {
            return ValidationOutcome.Invalid("Could not read the file header: ${e.message}")
        }

        return ValidationOutcome.Invalid(
            "This does not look like an ONNX model file. Make sure you downloaded the .onnx file directly.",
        )
    }

    sealed interface ImportResult {
        data class Success(val filePath: String) : ImportResult
        data class Failure(val reason: String) : ImportResult
    }

    private sealed interface ValidationOutcome {
        data object Valid : ValidationOutcome
        data class Invalid(val reason: String) : ValidationOutcome
    }

    private companion object {
        const val MIN_ONNX_SIZE_BYTES = 10_000L
        const val DOWNLOAD_URL_640M =
            "https://github.com/notAI-tech/NudeNet/releases/tag/v3.4-weights"
    }
}
