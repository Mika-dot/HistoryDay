package com.example.dayflash.video

import android.content.Context
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object MontageBuilder {
    @OptIn(UnstableApi::class)
    suspend fun build(context: Context, inputs: List<File>, output: File): Boolean {
        if (inputs.isEmpty()) return false
        output.parentFile?.mkdirs()
        val raw = File(output.parentFile, "${output.nameWithoutExtension}.raw.mp4")

        val joined = withContext(Dispatchers.IO) {
            VideoConcatenator.concatenate(inputs, raw)
        }
        if (!joined) {
            raw.delete()
            return false
        }

        val enhanced = ColorGradeProcessor.enhance(context, raw, output)
        if (enhanced) {
            raw.delete()
            return true
        }

        return withContext(Dispatchers.IO) {
            output.delete()
            val fallback = runCatching { raw.renameTo(output) }.getOrDefault(false) ||
                runCatching {
                    raw.copyTo(output, overwrite = true)
                    raw.delete()
                    true
                }.getOrDefault(false)
            fallback && output.exists() && output.length() > 0
        }
    }
}
