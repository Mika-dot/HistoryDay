package com.example.dayflash.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.dayflash.R
import com.example.dayflash.capture.CaptureActivity

object NotificationHelper {
    const val CHANNEL_ID = "dayflash_capture"
    const val NOTIFICATION_ID = 7001

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notification_channel_description)
            }
        )
    }

    fun show(context: Context) {
        val captureIntent = Intent(context, CaptureActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(CaptureActivity.EXTRA_AUTO_RECORD, true)
        }
        val capturePending = PendingIntent.getActivity(
            context,
            100,
            captureIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val snoozePending = PendingIntent.getBroadcast(
            context,
            101,
            Intent(context, ReminderActionReceiver::class.java).setAction(ReminderActionReceiver.ACTION_SNOOZE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val skipPending = PendingIntent.getBroadcast(
            context,
            102,
            Intent(context, ReminderActionReceiver::class.java).setAction(ReminderActionReceiver.ACTION_SKIP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_moment)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(context.getString(R.string.notification_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setColor(ContextCompat.getColor(context, R.color.primary))
            .setAutoCancel(true)
            .setContentIntent(capturePending)
            .addAction(R.drawable.ic_stat_moment, context.getString(R.string.notification_snooze), snoozePending)
            .addAction(R.drawable.ic_stat_moment, context.getString(R.string.notification_skip), skipPending)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    fun dismiss(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}
