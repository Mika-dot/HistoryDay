package com.example.dayflash.video

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

@UnstableApi
object MontageBuilder {
    @Suppress("DEPRECATION")
    suspend fun build(context: Context, inputs: List<File>, output: File): Boolean =
        withContext(Dispatchers.Main.immediate) {
            val validInputs = inputs
                .filter { it.exists() && it.length() > 0L }
                .sortedBy { it.nameWithoutExtension.toLongOrNull() ?: it.lastModified() }

            if (validInputs.isEmpty()) return@withContext false

            output.parentFile?.mkdirs()
            output.delete()

            suspendCancellableCoroutine { continuation ->
                val items = validInputs.map { file ->
                    EditedMediaItem.Builder(
                        MediaItem.fromUri(Uri.fromFile(file))
                    ).build()
                }

                // A real Media3 timeline. Every clip is decoded and encoded into one
                // consistent stream, so timestamps and codec settings cannot freeze
                // after the first CameraX MP4 file.
                val sequence = EditedMediaItemSequence.Builder(items)
                    .experimentalSetForceAudioTrack(true)
                    .build()
                val composition = Composition.Builder(sequence).build()

                lateinit var transformer: Transformer
                val listener = object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        val success = output.exists() && output.length() > 0L
                        if (continuation.isActive) continuation.resume(success)
                    }

                    override fun onError(
                        composition: Composition,
                        result: ExportResult,
                        exception: ExportException,
                    ) {
                        output.delete()
                        if (continuation.isActive) continuation.resume(false)
                    }
                }

                transformer = Transformer.Builder(context.applicationContext)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(listener)
                    .build()

                continuation.invokeOnCancellation {
                    runCatching { transformer.cancel() }
                    output.delete()
                }

                runCatching {
                    transformer.start(composition, output.absolutePath)
                }.onFailure {
                    output.delete()
                    if (continuation.isActive) continuation.resume(false)
                }
            }
        }
}
