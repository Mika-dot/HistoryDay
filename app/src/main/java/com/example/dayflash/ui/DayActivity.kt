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
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.dayflash.R
import com.example.dayflash.data.AppDatabase
import com.example.dayflash.data.ClipEntity
import com.example.dayflash.databinding.ActivityDayBinding
import com.example.dayflash.video.MontageBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class DayActivity : ComponentActivity() {
    private lateinit var binding: ActivityDayBinding
    private lateinit var day: String
    private lateinit var output: File
    private var clips: List<ClipEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        binding = ActivityDayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        day = intent.getStringExtra(EXTRA_DAY) ?: return finish()
        output = File(filesDir, "videos/days/$day.mp4")
        val date = runCatching { LocalDate.parse(day) }.getOrNull()
        binding.titleText.text = date?.format(
            DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(Locale.getDefault())
        ) ?: day

        binding.videoView.setMediaController(MediaController(this))
        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)
        binding.mapView.controller.setZoom(15.0)

        binding.backButton.setOnClickListener { finish() }
        binding.buildButton.setOnClickListener { buildVideo() }
        binding.shareButton.setOnClickListener { shareVideo() }

        if (output.exists()) play(output) else showEmptyPreview()
        loadMoments()
    }

    private fun loadMoments() {
        lifecycleScope.launch {
            clips = withContext(Dispatchers.IO) {
                AppDatabase.get(this@DayActivity).clipDao().clipsForDay(day)
                    .filter { File(it.path).exists() }
            }

            val uniquePlaces = clips.mapNotNull { it.placeName?.takeIf(String::isNotBlank) }.distinct().size
            binding.momentsSummaryText.text = getString(
                R.string.day_summary,
                clips.size,
                uniquePlaces,
                clips.count { it.latitude != null && it.longitude != null },
            )

            binding.timelineList.layoutManager = LinearLayoutManager(this@DayActivity)
            binding.timelineList.adapter = MomentAdapter(clips) { clip -> playMoment(clip) }
            binding.timelineList.isNestedScrollingEnabled = false
            renderMap(clips)
        }
    }

    private fun renderMap(items: List<ClipEntity>) {
        val located = items.filter { it.latitude != null && it.longitude != null }
        binding.mapEmptyText.visibility = if (located.isEmpty()) View.VISIBLE else View.GONE
        binding.mapView.visibility = if (located.isEmpty()) View.INVISIBLE else View.VISIBLE
        binding.mapView.overlays.clear()
        if (located.isEmpty()) return

        val points = located.map { GeoPoint(it.latitude!!, it.longitude!!) }
        if (points.size > 1) {
            binding.mapView.overlays.add(Polyline().apply {
                setPoints(points)
                outlinePaint.strokeWidth = 6f
            })
        }

        located.forEachIndexed { index, clip ->
            val point = GeoPoint(clip.latitude!!, clip.longitude!!)
            val time = Instant.ofEpochMilli(clip.capturedAt)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm"))
            binding.mapView.overlays.add(Marker(binding.mapView).apply {
                position = point
                title = clip.placeName ?: getString(R.string.map_point_number, index + 1)
                snippet = time
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            })
        }

        binding.mapView.post {
            if (points.size == 1) {
                binding.mapView.controller.setCenter(points.first())
                binding.mapView.controller.setZoom(17.0)
            } else {
                binding.mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(points), true, 70)
            }
            binding.mapView.invalidate()
        }
    }

    private fun playMoment(clip: ClipEntity) {
        val file = File(clip.path)
        if (!file.exists()) return
        play(file)
    }

    private fun buildVideo() {
        setBuilding(true)
        lifecycleScope.launch {
            val inputs = withContext(Dispatchers.IO) {
                AppDatabase.get(this@DayActivity).clipDao().clipsForDay(day)
                    .map { File(it.path) }
                    .filter { it.exists() }
            }
            val result = if (inputs.isEmpty()) false else MontageBuilder.build(this@DayActivity, inputs, output)
            setBuilding(false)
            if (result) {
                play(output)
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

    private fun play(file: File) {
        binding.previewPlaceholder.visibility = View.GONE
        binding.videoView.visibility = View.VISIBLE
        binding.shareButton.isEnabled = output.exists()
        binding.videoView.setVideoPath(file.absolutePath)
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

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) binding.mapView.onResume()
    }

    override fun onPause() {
        if (::binding.isInitialized) binding.mapView.onPause()
        super.onPause()
    }

    companion object {
        const val EXTRA_DAY = "day"
    }
}
