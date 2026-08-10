package com.example.dayflash.capture

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.example.dayflash.R
import com.example.dayflash.data.AppDatabase
import com.example.dayflash.data.ClipEntity
import com.example.dayflash.databinding.ActivityCaptureBinding
import com.example.dayflash.location.MomentLocation
import com.example.dayflash.location.MomentLocationResolver
import com.example.dayflash.poi.PoiGeofenceManager
import com.example.dayflash.poi.PoiRefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class CaptureActivity : ComponentActivity() {
    private lateinit var binding: ActivityCaptureBinding
    private var cameraProvider: ProcessCameraProvider? = null
    private var recording: Recording? = null
    private var countdown: CountDownTimer? = null
    private var started = false
    private var switchingCamera = false
    private var lensFacing = CameraSelector.LENS_FACING_FRONT
    private var locationDeferred: Deferred<MomentLocation?>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            finish()
            return
        }

        locationDeferred = saveScope.async {
            runCatching { MomentLocationResolver.resolve(applicationContext) }.getOrNull()
        }

        lensFacing = CapturePreferences.getLensFacing(this)
        binding.switchCameraButton.setOnClickListener { switchCamera() }
        updateCameraLabel()
        startCamera()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        countdown?.cancel()
        recording?.close()
        recording = null
        started = false

        val requestedSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val selector = if (provider.hasCamera(requestedSelector)) {
            requestedSelector
        } else {
            lensFacing = CameraSelector.LENS_FACING_BACK
            CapturePreferences.setLensFacing(this, lensFacing)
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.previewView.surfaceProvider)
        }

        val qualitySelector = QualitySelector.fromOrderedList(
            listOf(Quality.FHD, Quality.HD, Quality.SD),
            FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD),
        )
        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .build()

        val capture = VideoCapture.Builder(recorder)
            .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
            .build()

        provider.unbindAll()
        provider.bindToLifecycle(this, selector, preview, capture)
        updateCameraLabel()

        binding.captureProgress.progress = 0
        binding.countdownText.text = getString(R.string.capture_ready)
        binding.previewView.postDelayed({ beginRecording(capture) }, CAMERA_WARMUP_MS)
    }

    private fun beginRecording(capture: VideoCapture<Recorder>) {
        if (started || isFinishing || switchingCamera) return
        started = true

        val day = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
        val dir = File(filesDir, "videos/clips/$day").apply { mkdirs() }
        val file = File(dir, "${System.currentTimeMillis()}.mp4")

        var pending = capture.output.prepareRecording(this, FileOutputOptions.Builder(file).build())
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled()
        }

        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> startCountdown()
                is VideoRecordEvent.Finalize -> handleFinalize(event, file, day)
            }
        }
    }

    private fun startCountdown() {
        binding.recordingDot.visibility = View.VISIBLE
        countdown = object : CountDownTimer(RECORDING_MS, 50) {
            override fun onTick(ms: Long) {
                val elapsed = (RECORDING_MS - ms).coerceAtLeast(0L)
                binding.captureProgress.progress = ((elapsed * 100) / RECORDING_MS).toInt()
                binding.countdownText.text = getString(R.string.capture_seconds, ms / 1000f)
            }

            override fun onFinish() {
                binding.captureProgress.progress = 100
                binding.countdownText.text = getString(R.string.capture_saved)
                recording?.stop()
            }
        }.start()
    }

    private fun handleFinalize(event: VideoRecordEvent.Finalize, file: File, day: String) {
        recording = null
        countdown?.cancel()
        binding.recordingDot.visibility = View.INVISIBLE

        if (switchingCamera) {
            file.delete()
            switchingCamera = false
            bindCamera()
            return
        }

        if (!event.hasError() && file.exists() && file.length() > 0L) {
            saveScope.launch {
                val location = runCatching {
                    withTimeoutOrNull(LOCATION_RESULT_TIMEOUT_MS) { locationDeferred?.await() }
                }.getOrNull()
                val suggestedName = intent.getStringExtra(EXTRA_POI_NAME)?.trim()?.takeIf { it.isNotEmpty() }
                val suggestedLat = intent.getDoubleExtra(EXTRA_POI_LAT, Double.NaN)
                val suggestedLon = intent.getDoubleExtra(EXTRA_POI_LON, Double.NaN)
                val useSuggestedPlace = location != null && suggestedName != null &&
                    suggestedLat.isFinite() && suggestedLon.isFinite() &&
                    distanceMeters(location.latitude, location.longitude, suggestedLat, suggestedLon) <= POI_NAME_MAX_DISTANCE_METERS

                AppDatabase.get(applicationContext).clipDao().insert(
                    ClipEntity(
                        path = file.absolutePath,
                        capturedAt = System.currentTimeMillis(),
                        dayKey = day,
                        latitude = location?.latitude,
                        longitude = location?.longitude,
                        placeName = if (useSuggestedPlace) suggestedName else location?.placeName,
                        osmType = location?.osmType,
                        osmId = location?.osmId,
                    )
                )
                if (PoiGeofenceManager.isEnabled(applicationContext)) {
                    PoiRefreshWorker.enqueue(applicationContext, force = false)
                }
            }
        } else {
            file.delete()
        }

        binding.captureProgress.postDelayed({ finishAndRemoveTask() }, FINISH_DELAY_MS)
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val result = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, result)
        return result[0]
    }

    private fun switchCamera() {
        if (switchingCamera) return
        switchingCamera = true
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }
        CapturePreferences.setLensFacing(this, lensFacing)
        updateCameraLabel()

        if (recording != null) {
            countdown?.cancel()
            recording?.stop()
        } else {
            switchingCamera = false
            bindCamera()
        }
    }

    private fun updateCameraLabel() {
        val front = lensFacing == CameraSelector.LENS_FACING_FRONT
        binding.cameraNameText.setText(if (front) R.string.front_camera else R.string.back_camera)
        binding.switchCameraButton.contentDescription = getString(
            if (front) R.string.switch_to_back_camera else R.string.switch_to_front_camera
        )
    }

    override fun onDestroy() {
        countdown?.cancel()
        recording?.close()
        recording = null
        cameraProvider?.unbindAll()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_AUTO_RECORD = "auto_record"
        const val EXTRA_POI_NAME = "poi_name"
        const val EXTRA_POI_LAT = "poi_lat"
        const val EXTRA_POI_LON = "poi_lon"
        private const val CAMERA_WARMUP_MS = 650L
        private const val RECORDING_MS = 2_000L
        private const val FINISH_DELAY_MS = 220L
        private const val LOCATION_RESULT_TIMEOUT_MS = 4_000L
        private const val POI_NAME_MAX_DISTANCE_METERS = 500f
        private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
