package com.example.dayflash.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.ZonedDateTime
import kotlin.random.Random

object ReminderScheduler {
    private const val REQUEST_CODE = 901
    private const val PREFS = "dayflash_prefs"
    private const val ENABLED = "enabled"
    private const val NEXT_TRIGGER = "next_trigger"

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ENABLED, enabled)
            .apply()
        if (enabled) scheduleNext(context) else cancel(context)
    }

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    fun nextTriggerAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(NEXT_TRIGGER, 0L)

    fun scheduleNext(context: Context, from: ZonedDateTime = ZonedDateTime.now()) {
        if (!isEnabled(context)) return

        val startHour = 8
        val endHour = 22
        var targetHour = from.withMinute(0).withSecond(0).withNano(0).plusHours(1)
        if (targetHour.hour > endHour) {
            targetHour = targetHour.plusDays(1).withHour(startHour)
        } else if (targetHour.hour < startHour) {
            targetHour = targetHour.withHour(startHour)
        }

        val randomMinute = Random.nextInt(4, 56)
        scheduleAt(context, targetHour.withMinute(randomMinute).toInstant().toEpochMilli())
    }

    fun scheduleAfter(context: Context, minutes: Int) {
        if (!isEnabled(context)) return
        val delay = minutes.coerceAtLeast(1) * 60_000L
        scheduleAt(context, System.currentTimeMillis() + delay)
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(receiverPendingIntent(context))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(NEXT_TRIGGER)
            .apply()
    }

    private fun scheduleAt(context: Context, triggerAt: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(NEXT_TRIGGER, triggerAt)
            .apply()

        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAt,
            receiverPendingIntent(context),
        )
    }

    private fun receiverPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, ReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
