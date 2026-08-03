package com.example.dayflash.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.MediaController
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.example.dayflash.R
import com.example.dayflash.data.AppDatabase
import com.example.dayflash.databinding.ActivityDayBinding
import com.example.dayflash.video.MontageBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class DayActivity : ComponentActivity() {
    private lateinit var binding: ActivityDayBinding
    private lateinit var day: String
    private lateinit var output: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        day = intent.getStringExtra(EXTRA_DAY) ?: return finish()
        output = File(filesDir, "videos/days/$day.mp4")
        val date = runCatching { LocalDate.parse(day) }.getOrNull()
        binding.titleText.text = date?.format(
            DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(Locale.getDefault())
        ) ?: day
        binding.videoView.setMediaController(MediaController(this))

        binding.backButton.setOnClickListener { finish() }
        binding.buildButton.setOnClickListener { buildVideo() }
        binding.shareButton.setOnClickListener { shareVideo() }
        if (output.exists()) play() else showEmptyPreview()
    }

    private fun buildVideo() {
        setBuilding(true)
        lifecycleScope.launch {
            val clips = withContext(Dispatchers.IO) {
                AppDatabase.get(this@DayActivity).clipDao().clipsForDay(day)
                    .map { File(it.path) }
                    .filter { it.exists() }
            }
            val result = if (clips.isEmpty()) false else MontageBuilder.build(this@DayActivity, clips, output)
            setBuilding(false)
            if (result) {
                play()
            } else {
                Toast.makeText(this@DayActivity, R.string.build_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setBuilding(building: Boolean) {
        binding.buildProgress.visibility = if (building) View.VISIBLE else View.GONE
        binding.buildButton.isEnabled = !building
        binding.shareButton.isEnabled = !building && output.exists()
        binding.buildButton.setText(if (building) R.string.building_video else R.string.rebuild_video)
    }

    private fun showEmptyPreview() {
        binding.previewPlaceholder.visibility = View.VISIBLE
        binding.videoView.visibility = View.INVISIBLE
        binding.shareButton.isEnabled = false
    }

    private fun play() {
        binding.previewPlaceholder.visibility = View.GONE
        binding.videoView.visibility = View.VISIBLE
        binding.shareButton.isEnabled = true
        binding.videoView.setVideoPath(output.absolutePath)
        binding.videoView.setOnPreparedListener {
            it.isLooping = true
            binding.videoView.start()
        }
    }

    private fun shareVideo() {
        if (!output.exists()) {
            Toast.makeText(this, R.string.build_first, Toast.LENGTH_SHORT).show()
            return
        }
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.files", output)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, getString(R.string.share_memory)))
    }

    companion object {
        const val EXTRA_DAY = "day"
    }
}
