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
import com.example.dayflash.poi.PoiData

object NotificationHelper {
    const val CHANNEL_ID = "dayflash_capture"
    const val POI_CHANNEL_ID = "dayflash_places"
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
        manager.createNotificationChannel(
            NotificationChannel(
                POI_CHANNEL_ID,
                context.getString(R.string.poi_notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.poi_notification_channel_description)
            }
        )
    }

    fun show(context: Context) {
        val intent = Intent(context, CaptureActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(CaptureActivity.EXTRA_AUTO_RECORD, true)
        }
        val pending = PendingIntent.getActivity(
            context,
            100,
            intent,
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
            .setContentIntent(pending)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    fun showPoi(context: Context, poi: PoiData) {
        val intent = Intent(context, CaptureActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(CaptureActivity.EXTRA_AUTO_RECORD, true)
            putExtra(CaptureActivity.EXTRA_POI_NAME, poi.name)
            putExtra(CaptureActivity.EXTRA_POI_LAT, poi.latitude)
            putExtra(CaptureActivity.EXTRA_POI_LON, poi.longitude)
        }
        val pending = PendingIntent.getActivity(
            context,
            10_000 + (poi.requestId.hashCode() and 0x0FFF),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, POI_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_moment)
            .setContentTitle(context.getString(R.string.poi_notification_title, poi.name))
            .setContentText(context.getString(R.string.poi_notification_text))
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    context.getString(R.string.poi_notification_big_text, poi.name)
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setColor(ContextCompat.getColor(context, R.color.primary))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        NotificationManagerCompat.from(context).notify(
            8_000 + (poi.requestId.hashCode() and 0x0FFF),
            notification,
        )
    }
}
