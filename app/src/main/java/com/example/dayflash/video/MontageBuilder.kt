package com.example.dayflash.video

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.example.dayflash.data.ClipEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.resume

@UnstableApi
object MontageBuilder {
    suspend fun build(
        context: Context,
        clips: List<ClipEntity>,
        output: File,
        overlayMode: OverlayMode,
    ): Boolean = withContext(Dispatchers.Main.immediate) {
        val validClips = clips
            .filter { File(it.path).let { file -> file.exists() && file.length() > 0L } }
            .sortedBy { it.capturedAt }

        if (validClips.isEmpty()) return@withContext false

        output.parentFile?.mkdirs()
        output.delete()

        suspendCancellableCoroutine { continuation ->
            val items: List<EditedMediaItem> = validClips.map { clip ->
                val builder = EditedMediaItem.Builder(
                    MediaItem.fromUri(Uri.fromFile(File(clip.path)))
                )
                createEffects(clip, overlayMode)?.let { builder.setEffects(it) }
                builder.build()
            }

            val sequence = EditedMediaItemSequence.Builder(items).build()
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

            try {
                transformer.start(composition, output.absolutePath)
            } catch (_: Throwable) {
                output.delete()
                if (continuation.isActive) continuation.resume(false)
            }
        }
    }

    private fun createEffects(clip: ClipEntity, mode: OverlayMode): Effects? {
        if (mode == OverlayMode.NONE) return null
        val label = labelFor(clip, mode).takeIf { it.isNotBlank() } ?: return null
        val text = SpannableString("  $label  ").apply {
            setSpan(ForegroundColorSpan(Color.WHITE), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(BackgroundColorSpan(0x99000000.toInt()), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val settings = StaticOverlaySettings.Builder()
            .setBackgroundFrameAnchor(-0.90f, -0.88f)
            .setOverlayFrameAnchor(-1f, -1f)
            .setScale(0.42f, 0.42f)
            .build()
        val overlay = TextOverlay.createStaticTextOverlay(text, settings)
        return Effects(emptyList(), listOf(OverlayEffect(listOf(overlay))))
    }

    private fun labelFor(clip: ClipEntity, mode: OverlayMode): String {
        val time = Instant.ofEpochMilli(clip.capturedAt)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm"))
        val place = clip.placeName?.trim()?.takeIf { it.isNotBlank() }
        val coordinates = if (clip.latitude != null && clip.longitude != null) {
            String.format(Locale.US, "%.5f, %.5f", clip.latitude, clip.longitude)
        } else null

        return when (mode) {
            OverlayMode.TIME_PLACE -> if (place != null) "$time  •  $place" else time
            OverlayMode.TIME -> time
            OverlayMode.PLACE -> place ?: time
            OverlayMode.COORDINATES -> coordinates ?: time
            OverlayMode.NONE -> ""
        }
    }
}
