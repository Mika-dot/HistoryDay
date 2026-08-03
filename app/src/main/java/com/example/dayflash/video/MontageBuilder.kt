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
    suspend fun build(context: Context, inputs: List<File>, output: File): Boolean =
        withContext(Dispatchers.Main.immediate) {
            val validInputs = inputs
                .filter { it.exists() && it.length() > 0L }
                .sortedBy { it.nameWithoutExtension.toLongOrNull() ?: it.lastModified() }

            if (validInputs.isEmpty()) return@withContext false

            output.parentFile?.mkdirs()
            output.delete()

            suspendCancellableCoroutine { continuation ->
                val items: List<EditedMediaItem> = validInputs.map { file ->
                    EditedMediaItem.Builder(
                        MediaItem.fromUri(Uri.fromFile(file))
                    ).build()
                }

                // Media3 1.6.1 creates a real sequential timeline and transcodes
                // all CameraX clips into one consistent H.264/AAC stream.
                val sequence: EditedMediaItemSequence =
                    EditedMediaItemSequence.Builder(items).build()
                val composition: Composition = Composition.Builder(sequence).build()

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

                try {
                    transformer.start(composition, output.absolutePath)
                } catch (_: Throwable) {
                    output.delete()
                    if (continuation.isActive) continuation.resume(false)
                }
            }
        }
}
