package com.example.dayflash.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.dayflash.R
import com.example.dayflash.data.AppDatabase
import com.example.dayflash.data.ClipEntity
import com.example.dayflash.databinding.ActivityDayBinding
import com.example.dayflash.video.MontageBuilder
import com.example.dayflash.video.OverlayMode
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.CopyrightOverlay
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
    private lateinit var player: ExoPlayer
    private var clips: List<ClipEntity> = emptyList()
    private var overlayMode: OverlayMode = OverlayMode.TIME_PLACE
    private var previewClip: ClipEntity? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        player = ExoPlayer.Builder(this).build().also {
            it.repeatMode = Player.REPEAT_MODE_ONE
            binding.playerView.player = it
        }

        day = intent.getStringExtra(EXTRA_DAY) ?: return finish()
        output = File(filesDir, "videos/days/$day.mp4")
        val date = runCatching { LocalDate.parse(day) }.getOrNull()
        binding.titleText.text = date?.format(
            DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(Locale.getDefault())
        ) ?: day

        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)
        binding.mapView.setUseDataConnection(true)
        binding.mapView.controller.setZoom(15.0)

        binding.backButton.setOnClickListener { finish() }
        binding.buildButton.setOnClickListener { buildVideo() }
        binding.shareButton.setOnClickListener { shareVideo() }
        setupOverlaySelector()

        if (output.exists()) playFinal() else showEmptyPreview()
        loadMoments()
    }

    private fun setupOverlaySelector() {
        binding.overlayGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            overlayMode = when (checkedIds.firstOrNull()) {
                R.id.overlayTimeChip -> OverlayMode.TIME
                R.id.overlayPlaceChip -> OverlayMode.PLACE
                R.id.overlayGpsChip -> OverlayMode.COORDINATES
                R.id.overlayNoneChip -> OverlayMode.NONE
                else -> OverlayMode.TIME_PLACE
            }
            previewClip?.let { updatePreviewOverlay(it) }
        }
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
            renderMomentsStrip(clips)
            renderMap(clips)
        }
    }

    private fun renderMomentsStrip(items: List<ClipEntity>) {
        binding.momentsStrip.removeAllViews()
        items.forEach { clip ->
            val time = formatTime(clip.capturedAt)
            val chip = Chip(this).apply {
                text = time
                isCheckable = false
                isClickable = true
                setOnClickListener { playMoment(clip) }
            }
            val params = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(8) }
            binding.momentsStrip.addView(chip, params)
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
            binding.mapView.overlays.add(Marker(binding.mapView).apply {
                position = point
                title = clip.placeName ?: getString(R.string.map_point_number, index + 1)
                snippet = formatTime(clip.capturedAt)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                setOnMarkerClickListener { _, _ ->
                    playMoment(clip)
                    showInfoWindow()
                    true
                }
            })
        }
        binding.mapView.overlays.add(CopyrightOverlay(this))

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
        previewClip = clip
        playFile(file)
        updatePreviewOverlay(clip)
    }

    private fun playFinal() {
        previewClip = null
        binding.previewOverlayText.visibility = View.GONE
        playFile(output)
    }

    private fun playFile(file: File) {
        binding.previewPlaceholder.visibility = View.GONE
        binding.playerView.visibility = View.VISIBLE
        binding.shareButton.isEnabled = output.exists()
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player.prepare()
        player.playWhenReady = true
    }

    private fun updatePreviewOverlay(clip: ClipEntity) {
        val label = overlayLabel(clip)
        binding.previewOverlayText.text = label
        binding.previewOverlayText.visibility = if (label.isBlank()) View.GONE else View.VISIBLE
    }

    private fun overlayLabel(clip: ClipEntity): String {
        val time = formatTime(clip.capturedAt)
        val place = clip.placeName?.trim()?.takeIf { it.isNotBlank() }
        val coordinates = if (clip.latitude != null && clip.longitude != null) {
            String.format(Locale.US, "%.5f, %.5f", clip.latitude, clip.longitude)
        } else null
        return when (overlayMode) {
            OverlayMode.TIME_PLACE -> if (place != null) "$time  •  $place" else time
            OverlayMode.TIME -> time
            OverlayMode.PLACE -> place ?: time
            OverlayMode.COORDINATES -> coordinates ?: time
            OverlayMode.NONE -> ""
        }
    }

    private fun buildVideo() {
        setBuilding(true)
        lifecycleScope.launch {
            val inputs = withContext(Dispatchers.IO) {
                AppDatabase.get(this@DayActivity).clipDao().clipsForDay(day)
                    .filter { File(it.path).exists() }
            }
            val result = if (inputs.isEmpty()) {
                false
            } else {
                MontageBuilder.build(this@DayActivity, inputs, output, overlayMode)
            }
            setBuilding(false)
            if (result) {
                playFinal()
            } else {
                Toast.makeText(this@DayActivity, R.string.build_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setBuilding(building: Boolean) {
        binding.buildProgress.visibility = if (building) View.VISIBLE else View.GONE
        binding.buildButton.isEnabled = !building
        binding.shareButton.isEnabled = !building && output.exists()
        binding.overlayGroup.isEnabled = !building
        binding.buildButton.setText(if (building) R.string.building_video else R.string.rebuild_video)
    }

    private fun showEmptyPreview() {
        binding.previewPlaceholder.visibility = View.VISIBLE
        binding.playerView.visibility = View.INVISIBLE
        binding.previewOverlayText.visibility = View.GONE
        binding.shareButton.isEnabled = false
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

    override fun onDestroy() {
        binding.playerView.player = null
        player.release()
        super.onDestroy()
    }

    private fun formatTime(timestamp: Long): String =
        Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm"))

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_DAY = "day"
    }
}
