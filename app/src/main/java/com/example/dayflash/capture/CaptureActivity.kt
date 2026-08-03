package com.example.dayflash.capture

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.dayflash.R
import com.example.dayflash.data.AppDatabase
import com.example.dayflash.data.ClipEntity
import com.example.dayflash.databinding.ActivityCaptureBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    private var activeFile: File? = null
    private var lensFacing = CameraSelector.LENS_FACING_FRONT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            finish()
            return
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

        val preview = Preview.Builder().build()
            .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD))
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
        activeFile = file

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

        if (!event.hasError() && file.exists() && file.length() > 0) {
            lifecycleScope.launch(Dispatchers.IO) {
                AppDatabase.get(this@CaptureActivity).clipDao().insert(
                    ClipEntity(
                        path = file.absolutePath,
                        capturedAt = System.currentTimeMillis(),
                        dayKey = day,
                    )
                )
            }
        } else {
            file.delete()
        }

        binding.captureProgress.postDelayed({ finishAndRemoveTask() }, FINISH_DELAY_MS)
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
        private const val CAMERA_WARMUP_MS = 550L
        private const val RECORDING_MS = 2_000L
        private const val FINISH_DELAY_MS = 180L
    }
}
