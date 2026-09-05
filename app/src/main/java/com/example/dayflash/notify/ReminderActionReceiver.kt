package com.example.dayflash.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        NotificationHelper.dismiss(context)
        if (intent?.action == ACTION_SNOOZE) {
            ReminderScheduler.scheduleAfter(context, SNOOZE_MINUTES)
        }
    }

    companion object {
        const val ACTION_SNOOZE = "com.example.dayflash.action.SNOOZE"
        const val ACTION_SKIP = "com.example.dayflash.action.SKIP"
        private const val SNOOZE_MINUTES = 10
    }
}
