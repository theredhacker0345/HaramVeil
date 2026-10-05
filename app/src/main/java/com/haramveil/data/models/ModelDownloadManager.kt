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

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.haramveil.MainActivity
import com.haramveil.R
import com.haramveil.utils.DispatcherProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

sealed interface ModelDownloadState {
    data class Available(
        val file: File,
    ) : ModelDownloadState

    data class Downloaded(
        val file: File,
    ) : ModelDownloadState

    data class Failed(
        val reason: String,
    ) : ModelDownloadState
}

class ModelDownloadManager(
    private val context: Context,
    private val dispatcherProvider: DispatcherProvider = DispatcherProvider(),
) {

    suspend fun downloadRapidOcrModelIfNeeded(): ModelDownloadState =
        downloadMutex.withLock {
            withContext(dispatcherProvider.io) {
                downloadLocked()
            }
        }

    /**
     * Runs one download pass. Two managers exist in the app (the OCR engine selector and
     * the engine itself), so the whole pass is serialized on a process-wide mutex: without
     * it both would write the same `rapidocr.onnx.part` prefix and corrupt each other.
     */
    private suspend fun downloadLocked(): ModelDownloadState {
        if (isModelReady()) {
            return ModelDownloadState.Available(modelFile())
        }

        val modelsDirectory = ensureModelsDirectory()
        val targetFile = File(modelsDirectory, ModelFileName)
        val partialFile = File(modelsDirectory, PartialFileName)
        createNotificationChannel()
        postProgressNotification(progressPercent = null, message = "Preparing FOSS OCR model download")

        repeat(MaxDownloadAttempts) {
            val outcome = try {
                downloadAttempt(partialFile, targetFile)
            } catch (cancellation: CancellationException) {
                // The .part prefix stays on disk so the next attempt resumes from it.
                rethrowCancellation(cancellation)
            } catch (rejected: DownloadRejectedException) {
                AttemptOutcome.Rejected(
                    reason = rejected.message ?: "RapidOCR model download failed.",
                    remindUser = false,
                )
            } catch (retryable: IOException) {
                // An interrupted transfer resumes from the retained .part file.
                AttemptOutcome.Retry(
                    reason = retryable.message ?: "RapidOCR model download was interrupted.",
                    discardPartial = false,
                )
            } catch (failure: Exception) {
                AttemptOutcome.Rejected(
                    reason = failure.message ?: "RapidOCR model download failed.",
                    remindUser = false,
                )
            }

            when (outcome) {
                is AttemptOutcome.Completed -> {
                    cancelProgressNotification()
                    postSetupReminderNotification(
                        title = "FOSS OCR ready",
                        message = "The private OCR model has been stored on-device. HaramVeil will not use the network for this model again.",
                    )
                    return ModelDownloadState.Downloaded(outcome.file)
                }

                is AttemptOutcome.Retry -> {
                    if (outcome.discardPartial) {
                        partialFile.delete()
                    }
                }

                is AttemptOutcome.Rejected -> {
                    cancelProgressNotification()
                    if (outcome.remindUser) {
                        postSetupReminderNotification()
                    }
                    return ModelDownloadState.Failed(outcome.reason)
                }
            }
        }

        cancelProgressNotification()
        return ModelDownloadState.Failed("RapidOCR model download failed after $MaxDownloadAttempts attempts.")
    }

    private suspend fun downloadAttempt(
        partialFile: File,
        targetFile: File,
    ): AttemptOutcome {
        val storedBytes = partialFile.takeIf { candidate -> candidate.isFile }?.length() ?: 0L
        val connection = (URL(ModelUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = ConnectTimeoutMs
            readTimeout = ReadTimeoutMs
            instanceFollowRedirects = true
            if (storedBytes > 0L) {
                setRequestProperty(RangeHeader, "bytes=$storedBytes-")
            }
        }

        try {
            connection.connect()
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_PARTIAL && storedBytes > 0L) {
                return streamResponseBody(
                    connection = connection,
                    partialFile = partialFile,
                    targetFile = targetFile,
                    resumeOffset = storedBytes,
                )
            }
            if (responseCode == RangeNotSatisfiableCode) {
                return AttemptOutcome.Retry(
                    reason = "The stored partial download no longer matches the server copy.",
                    discardPartial = true,
                )
            }
            if (responseCode !in 200..299) {
                return AttemptOutcome.Rejected(
                    reason = "Model download failed with HTTP $responseCode.",
                    remindUser = false,
                )
            }
            // Any other 2xx carries the whole body, so an existing partial prefix has to go.
            return streamResponseBody(
                connection = connection,
                partialFile = partialFile,
                targetFile = targetFile,
                resumeOffset = 0L,
            )
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun streamResponseBody(
        connection: HttpURLConnection,
        partialFile: File,
        targetFile: File,
        resumeOffset: Long,
    ): AttemptOutcome {
        val expectedTotalBytes = resolveExpectedTotalBytes(connection, resumeOffset)
        if (expectedTotalBytes != null && expectedTotalBytes > MaxModelBytes) {
            throw DownloadRejectedException(
                "RapidOCR model is larger than the $MaxModelBytes byte safety limit.",
            )
        }
        requireFreeSpace((expectedTotalBytes ?: 0L) - resumeOffset)

        val digest = MessageDigest.getInstance(Sha256Algorithm)
        val effectiveOffset = if (resumeOffset > 0L && digestPrefix(partialFile, digest, resumeOffset) == resumeOffset) {
            resumeOffset
        } else {
            digest.reset()
            0L
        }

        var lastNotifiedPercent = -1
        val storedBytes = appendResponseBody(
            connection = connection,
            partialFile = partialFile,
            offset = effectiveOffset,
            digest = digest,
        ) { writtenBytes ->
            if (expectedTotalBytes != null && expectedTotalBytes > 0L) {
                val percent = ((writtenBytes * 100L) / expectedTotalBytes).toInt().coerceIn(0, 100)
                if (percent != lastNotifiedPercent) {
                    lastNotifiedPercent = percent
                    postProgressNotification(
                        progressPercent = percent,
                        message = "Downloading private OCR model",
                    )
                }
            }
        }

        if (expectedTotalBytes != null && storedBytes != expectedTotalBytes) {
            return AttemptOutcome.Retry(
                reason = "Download ended early at $storedBytes of $expectedTotalBytes bytes; resuming.",
                discardPartial = false,
            )
        }

        val actualChecksum = digest.digest().toHexString()
        if (!actualChecksum.equals(ModelSha256, ignoreCase = true)) {
            partialFile.delete()
            return AttemptOutcome.Rejected(
                reason = "RapidOCR checksum verification failed.",
                remindUser = true,
            )
        }

        // Publish with a rename: a copy can be interrupted half way and leave a truncated
        // rapidocr.onnx that isModelReady() would happily accept.
        if (!partialFile.renameTo(targetFile)) {
            return AttemptOutcome.Rejected(
                reason = "Could not publish the downloaded OCR model file.",
                remindUser = false,
            )
        }
        return AttemptOutcome.Completed(targetFile)
    }

    private suspend fun appendResponseBody(
        connection: HttpURLConnection,
        partialFile: File,
        offset: Long,
        digest: MessageDigest,
        onProgress: (Long) -> Unit,
    ): Long = connection.inputStream.use { inputStream ->
        RandomAccessFile(partialFile, "rw").use { outputFile ->
            outputFile.seek(offset)
            outputFile.setLength(offset)
            val buffer = ByteArray(DownloadBufferSizeBytes)
            var storedBytes = offset
            while (true) {
                currentCoroutineContext().ensureActive()
                val readCount = inputStream.read(buffer)
                if (readCount <= 0) {
                    break
                }
                storedBytes += readCount
                enforceStreamingCap(storedBytes)
                outputFile.write(buffer, 0, readCount)
                digest.update(buffer, 0, readCount)
                onProgress(storedBytes)
            }
            storedBytes
        }
    }

    /** Guards against a server that streams an endless body: stop at the 128 MB ceiling. */
    private fun enforceStreamingCap(storedBytes: Long) {
        if (storedBytes > MaxModelBytes) {
            throw DownloadRejectedException(
                "RapidOCR model is larger than the $MaxModelBytes byte safety limit.",
            )
        }
    }

    private fun requireFreeSpace(additionalBytes: Long) {
        if (additionalBytes <= 0L) {
            return
        }
        val availableBytes = StatFs(ensureModelsDirectory().absolutePath).availableBytes
        if (availableBytes < additionalBytes + FreeSpaceHeadroomBytes) {
            throw DownloadRejectedException(
                "Not enough free storage left for the OCR model download.",
            )
        }
    }

    /** `Content-Range` total for a 206 response, otherwise the body length plus the prefix. */
    private fun resolveExpectedTotalBytes(
        connection: HttpURLConnection,
        resumeOffset: Long,
    ): Long? {
        val contentRange = connection.getHeaderField(ContentRangeHeader)
        if (!contentRange.isNullOrBlank()) {
            val total = contentRange.substringAfterLast('/', missingDelimiterValue = "")
                .trim()
                .toLongOrNull()
            if (total != null && total > 0L) {
                return total
            }
        }
        val contentLength = connection.contentLengthLong
        return if (contentLength > 0L) contentLength + resumeOffset else null
    }

    /** Digests the first [byteCount] bytes of [file], returning how many were hashed. */
    private suspend fun digestPrefix(
        file: File,
        digest: MessageDigest,
        byteCount: Long,
    ): Long {
        var digested = 0L
        file.inputStream().use { inputStream ->
            val buffer = ByteArray(DownloadBufferSizeBytes)
            while (digested < byteCount) {
                currentCoroutineContext().ensureActive()
                val requested = minOf(buffer.size.toLong(), byteCount - digested).toInt()
                val readCount = inputStream.read(buffer, 0, requested)
                if (readCount <= 0) {
                    break
                }
                digest.update(buffer, 0, readCount)
                digested += readCount
            }
        }
        return digested
    }

    fun isModelReady(): Boolean {
        val file = modelFile()
        return file.isFile && verifyChecksum(file)
    }

    @SuppressLint("MissingPermission")
    fun postSetupReminderNotification(
        title: String = "Finish FOSS OCR setup",
        message: String = "Download the private OCR model to enable the FOSS text engine.",
    ) {
        createNotificationChannel()
        NotificationManagerCompat.from(context).notify(
            SetupReminderNotificationId,
            NotificationCompat.Builder(context, NotificationChannelId)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(buildMainActivityPendingIntent())
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build(),
        )
    }

    fun modelFile(): File = File(ensureModelsDirectory(), ModelFileName)

    private fun ensureModelsDirectory(): File =
        File(context.filesDir, ModelsDirectoryName).apply { mkdirs() }

    private fun verifyChecksum(file: File): Boolean {
        if (!file.isFile) {
            return false
        }

        return runCatching {
            val digest = MessageDigest.getInstance(Sha256Algorithm)
            file.inputStream().use { inputStream ->
                val buffer = ByteArray(DownloadBufferSizeBytes)
                while (true) {
                    val readCount = inputStream.read(buffer)
                    if (readCount <= 0) {
                        break
                    }
                    digest.update(buffer, 0, readCount)
                }
            }
            digest.digest().toHexString().equals(ModelSha256, ignoreCase = true)
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    private fun postProgressNotification(
        progressPercent: Int?,
        message: String,
    ) {
        val builder = NotificationCompat.Builder(context, NotificationChannelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("HaramVeil model download")
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(buildMainActivityPendingIntent())

        if (progressPercent != null) {
            builder.setProgress(100, progressPercent, false)
        } else {
            builder.setProgress(0, 0, true)
        }

        NotificationManagerCompat.from(context).notify(DownloadNotificationId, builder.build())
    }

    private fun cancelProgressNotification() {
        NotificationManagerCompat.from(context).cancel(DownloadNotificationId)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return
        val existingChannel = notificationManager.getNotificationChannel(NotificationChannelId)
        if (existingChannel != null) {
            return
        }

        notificationManager.createNotificationChannel(
            NotificationChannel(
                NotificationChannelId,
                "HaramVeil Model Downloads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "One-time private model setup progress for HaramVeil."
            },
        )
    }

    private fun buildMainActivityPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            5005,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun rethrowCancellation(cancellation: CancellationException): Nothing =
        throw cancellation

    private fun ByteArray.toHexString(): String =
        joinToString(separator = "") { byte -> String.format(Locale.ROOT, "%02x", byte) }

    private sealed interface AttemptOutcome {
        data class Completed(
            val file: File,
        ) : AttemptOutcome

        data class Retry(
            val reason: String,
            val discardPartial: Boolean,
        ) : AttemptOutcome

        data class Rejected(
            val reason: String,
            val remindUser: Boolean,
        ) : AttemptOutcome
    }

    /** Failure that will not be fixed by trying again (disk space, size ceiling). */
    private class DownloadRejectedException(message: String) : IOException(message)

    companion object {
        private const val NotificationChannelId = "haramveil_model_downloads"
        private const val DownloadNotificationId = 5105
        private const val SetupReminderNotificationId = 5106
        private const val ModelsDirectoryName = "models"
        private const val ModelFileName = "rapidocr.onnx"
        private const val PartialFileName = "$ModelFileName.part"
        private const val DownloadBufferSizeBytes = 8_192
        private const val Sha256Algorithm = "SHA-256"
        private const val RangeHeader = "Range"
        private const val ContentRangeHeader = "Content-Range"
        private const val RangeNotSatisfiableCode = 416
        private const val ConnectTimeoutMs = 15_000
        private const val ReadTimeoutMs = 30_000
        private const val MaxDownloadAttempts = 2
        private const val FreeSpaceHeadroomBytes = 16L * 1024L * 1024L
        private const val MaxModelBytes = 128L * 1024L * 1024L

        private val downloadMutex = Mutex()

        const val ModelUrl =
            "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.7.0/onnx/PP-OCRv5/rec/latin_PP-OCRv5_rec_mobile_infer.onnx"
        const val ModelSha256 =
            "b20bd37c168a570f583afbc8cd7925603890efbcdc000a59e22c269d160b5f5a"
    }
}
