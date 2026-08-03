package com.example.dayflash.video

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

object VideoConcatenator {
    fun concatenate(inputs: List<File>, output: File): Boolean {
        if (inputs.isEmpty()) return false
        output.delete()
        var muxer: MediaMuxer? = null
        return try {
            val first = MediaExtractor().apply { setDataSource(inputs.first().absolutePath) }
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackMap = mutableMapOf<String, Int>()
            for (i in 0 until first.trackCount) {
                val format = first.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    trackMap[mime.substringBefore('/')] = muxer.addTrack(format)
                }
            }
            first.release()
            muxer.start()

            val offsets = mutableMapOf("video" to 0L, "audio" to 0L)
            val buffer = ByteBuffer.allocateDirect(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()

            for (file in inputs) {
                val extractor = MediaExtractor().apply { setDataSource(file.absolutePath) }
                val clipDurations = mutableMapOf<String, Long>()
                for (track in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(track)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    val kind = mime.substringBefore('/')
                    val outTrack = trackMap[kind] ?: continue
                    if (kind != "video" && kind != "audio") continue
                    extractor.selectTrack(track)
                    var maxPts = 0L
                    while (true) {
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        val pts = extractor.sampleTime.coerceAtLeast(0L)
                        info.set(0, size, offsets.getValue(kind) + pts, extractor.sampleFlags)
                        muxer.writeSampleData(outTrack, buffer, info)
                        maxPts = maxOf(maxPts, pts)
                        extractor.advance()
                    }
                    clipDurations[kind] = maxPts + 33_333L
                    extractor.unselectTrack(track)
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                }
                extractor.release()
                for ((kind, duration) in clipDurations) offsets[kind] = offsets.getValue(kind) + duration
            }
            muxer.stop(); muxer.release(); muxer = null
            output.exists() && output.length() > 0
        } catch (_: Throwable) {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            output.delete()
            false
        }
    }
}
