package com.example.dayflash

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.example.dayflash.notify.NotificationHelper
import com.example.dayflash.worker.DailyWork

class DayFlashApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        NotificationHelper.createChannel(this)
        DailyWork.schedule(this)
    }
}
