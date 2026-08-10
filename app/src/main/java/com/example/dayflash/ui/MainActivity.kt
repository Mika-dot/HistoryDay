package com.example.dayflash.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.dayflash.R
import com.example.dayflash.capture.CaptureActivity
import com.example.dayflash.capture.CapturePreferences
import com.example.dayflash.data.AppDatabase
import com.example.dayflash.databinding.ActivityMainBinding
import com.example.dayflash.notify.ReminderScheduler
import com.example.dayflash.poi.PoiGeofenceManager
import com.example.dayflash.poi.PoiRefreshWorker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private lateinit var binding: ActivityMainBinding
    private var pendingPoiEnable = false

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val cameraOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (cameraOk) ReminderScheduler.setEnabled(this, true)
        refreshStatus()
        refreshPoiStatus()
    }

    private val locationPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (pendingPoiEnable) continuePoiEnable()
        refreshPoiStatus()
    }

    private val backgroundLocationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (pendingPoiEnable) continuePoiEnable()
        refreshPoiStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val adapter = DaysAdapter { day ->
            startActivity(Intent(this, DayActivity::class.java).putExtra(DayActivity.EXTRA_DAY, day))
        }
        binding.daysList.layoutManager = LinearLayoutManager(this)
        binding.daysList.adapter = adapter
        binding.daysList.itemAnimator = null

        lifecycleScope.launch {
            AppDatabase.get(this@MainActivity).clipDao().observeDays().collect { days ->
                adapter.submit(days)
                binding.emptyState.visibility = if (days.isEmpty()) View.VISIBLE else View.GONE
                binding.daysList.visibility = if (days.isEmpty()) View.GONE else View.VISIBLE
                binding.momentsCount.text = resources.getQuantityString(
                    R.plurals.moments_count,
                    days.sumOf { it.count },
                    days.sumOf { it.count },
                )
            }
        }

        setupCameraToggle()

        binding.enableButton.setOnClickListener {
            if (ReminderScheduler.isEnabled(this)) {
                ReminderScheduler.setEnabled(this, false)
                refreshStatus()
            } else {
                requestPermissionsAndEnable()
            }
        }

        binding.testButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startActivity(Intent(this, CaptureActivity::class.java))
            } else {
                requestPermissionsAndEnable()
            }
        }

        binding.poiButton.setOnClickListener {
            if (PoiGeofenceManager.isEnabled(this)) {
                pendingPoiEnable = false
                PoiGeofenceManager.setEnabled(this, false)
                refreshPoiStatus()
            } else {
                pendingPoiEnable = true
                continuePoiEnable()
            }
        }

        refreshStatus()
        refreshPoiStatus()
        requestLocationForExistingInstallIfNeeded()
    }

    private fun setupCameraToggle() {
        val frontSelected = CapturePreferences.getLensFacing(this) == CameraSelector.LENS_FACING_FRONT
        binding.cameraToggle.check(if (frontSelected) R.id.frontButton else R.id.backButton)
        binding.cameraToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val lens = if (checkedId == R.id.frontButton) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            CapturePreferences.setLensFacing(this, lens)
        }
    }

    private fun requestPermissionsAndEnable() {
        val list = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        permissions.launch(list.toTypedArray())
    }

    private fun requestLocationForExistingInstallIfNeeded() {
        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!cameraGranted || coarseGranted || fineGranted) return

        val prefs = getSharedPreferences(LOCATION_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(LOCATION_PROMPTED, false)) return
        prefs.edit().putBoolean(LOCATION_PROMPTED, true).apply()
        locationPermissions.launch(
            arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        )
    }

    private fun continuePoiEnable() {
        if (!PoiGeofenceManager.hasForegroundLocation(this)) {
            locationPermissions.launch(
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                )
            )
            return
        }

        if (!PoiGeofenceManager.hasBackgroundLocation(this)) {
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                backgroundLocationPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.poi_background_title)
                    .setMessage(R.string.poi_background_message)
                    .setNegativeButton(R.string.cancel) { _, _ ->
                        pendingPoiEnable = false
                        refreshPoiStatus()
                    }
                    .setPositiveButton(R.string.open_settings) { _, _ ->
                        startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:$packageName"),
                            )
                        )
                    }
                    .show()
            }
            return
        }

        pendingPoiEnable = false
        PoiGeofenceManager.setEnabled(this, true)
        PoiRefreshWorker.enqueue(this, force = true)
        refreshPoiStatus()
    }

    override fun onResume() {
        super.onResume()
        if (pendingPoiEnable && PoiGeofenceManager.hasForegroundLocation(this) && PoiGeofenceManager.hasBackgroundLocation(this)) {
            continuePoiEnable()
        }
        if (PoiGeofenceManager.isEnabled(this) && PoiGeofenceManager.hasBackgroundLocation(this)) {
            PoiRefreshWorker.enqueue(this, force = false)
        }
        refreshStatus()
        refreshPoiStatus()
    }

    private fun refreshStatus() {
        val enabled = ReminderScheduler.isEnabled(this)
        binding.statusText.setText(if (enabled) R.string.status_on else R.string.status_off)
        binding.statusDot.setBackgroundResource(if (enabled) R.drawable.bg_status_dot_on else R.drawable.bg_status_dot_off)
        binding.enableButton.setText(if (enabled) R.string.disable_reminders else R.string.enable_reminders)
        binding.enableButton.setIconResource(if (enabled) R.drawable.ic_pause else R.drawable.ic_notification)

        val next = ReminderScheduler.nextTriggerAt(this)
        binding.nextReminderText.text = if (enabled && next > System.currentTimeMillis()) {
            val time = Instant.ofEpochMilli(next)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm"))
            getString(R.string.next_reminder_at, time)
        } else {
            getString(R.string.random_reminder_window)
        }
    }

    private fun refreshPoiStatus() {
        val enabled = PoiGeofenceManager.isEnabled(this)
        binding.poiButton.setText(if (enabled) R.string.poi_disable else R.string.poi_enable)
        binding.poiStatusText.text = when {
            enabled && !PoiGeofenceManager.hasForegroundLocation(this) -> getString(R.string.poi_status_need_location)
            enabled && !PoiGeofenceManager.hasBackgroundLocation(this) -> getString(R.string.poi_status_need_background)
            enabled -> getString(R.string.poi_status_on, PoiGeofenceManager.registeredCount(this))
            else -> getString(R.string.poi_status_off)
        }
    }

    companion object {
        private const val LOCATION_PREFS = "historyday_location_prefs"
        private const val LOCATION_PROMPTED = "location_prompted_v13"
    }
}
