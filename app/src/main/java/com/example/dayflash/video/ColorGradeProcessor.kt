package com.example.dayflash.video

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.HslAdjustment
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

@UnstableApi
object ColorGradeProcessor {
    suspend fun enhance(context: Context, input: File, output: File): Boolean =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                output.delete()

                val colorGrade = HslAdjustment.Builder()
                    .adjustSaturation(12f)
                    .adjustLightness(2f)
                    .build()

                val editedMediaItem = EditedMediaItem.Builder(
                    MediaItem.fromUri(Uri.fromFile(input))
                ).setEffects(
                    Effects(
                        emptyList(),
                        listOf(colorGrade),
                    )
                ).build()

                lateinit var transformer: Transformer
                val listener = object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        if (continuation.isActive) {
                            continuation.resume(output.exists() && output.length() > 0)
                        }
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

                runCatching { transformer.start(editedMediaItem, output.absolutePath) }
                    .onFailure {
                        output.delete()
                        if (continuation.isActive) continuation.resume(false)
                    }
            }
        }
}
