package com.example.dayflash.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        NotificationHelper.show(context)
        ReminderScheduler.scheduleNext(context)
    }
}
