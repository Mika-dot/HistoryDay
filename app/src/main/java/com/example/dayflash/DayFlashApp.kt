package com.example.dayflash

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.example.dayflash.notify.NotificationHelper
import com.example.dayflash.worker.DailyWork
import org.osmdroid.config.Configuration

class DayFlashApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)

        Configuration.getInstance().apply {
            userAgentValue = "HistoryDay/1.4 Android (+https://github.com/Mika-dot/HistoryDay)"
            tileFileSystemCacheMaxBytes = 64L * 1024L * 1024L
            tileFileSystemCacheTrimBytes = 48L * 1024L * 1024L
        }

        NotificationHelper.createChannel(this)
        DailyWork.schedule(this)
    }
}
