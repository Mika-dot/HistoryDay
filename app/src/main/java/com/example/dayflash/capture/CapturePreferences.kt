package com.example.dayflash.capture

import android.content.Context
import androidx.camera.core.CameraSelector

object CapturePreferences {
    private const val PREFS = "capture_preferences"
    private const val KEY_LENS = "lens_facing"

    fun getLensFacing(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_LENS, CameraSelector.LENS_FACING_FRONT)

    fun setLensFacing(context: Context, lensFacing: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_LENS, lensFacing)
            .apply()
    }
}
