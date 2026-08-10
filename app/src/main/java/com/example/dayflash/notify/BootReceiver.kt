package com.example.dayflash.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.dayflash.poi.PoiGeofenceManager
import com.example.dayflash.poi.PoiRefreshWorker
import com.example.dayflash.worker.DailyWork

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        ReminderScheduler.scheduleNext(context)
        DailyWork.schedule(context)
        if (PoiGeofenceManager.isEnabled(context)) {
            PoiRefreshWorker.enqueue(context, force = true)
        }
    }
}
