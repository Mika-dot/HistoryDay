package com.example.dayflash.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.dayflash.data.AppDatabase
import com.example.dayflash.video.MontageBuilder
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class DailyMontageWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val day = LocalDate.now().minusDays(1).toString()
        val clips = AppDatabase.get(applicationContext).clipDao().clipsForDay(day)
            .map { File(it.path) }
            .filter { it.exists() }

        if (clips.isNotEmpty()) {
            val output = File(applicationContext.filesDir, "videos/days/$day.mp4")
            MontageBuilder.build(applicationContext, clips, output)
        }
        DailyWork.schedule(applicationContext)
        return Result.success()
    }
}

object DailyWork {
    private const val NAME = "daily_montage"

    fun schedule(context: Context) {
        val now = ZonedDateTime.now()
        var next = now.withHour(0).withMinute(10).withSecond(0).withNano(0).plusDays(1)
        if (next.isBefore(now)) next = next.plusDays(1)
        val delay = Duration.between(now, next).toMillis()
        val request = OneTimeWorkRequestBuilder<DailyMontageWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
    }
}
