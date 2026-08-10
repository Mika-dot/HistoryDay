package com.example.dayflash.poi

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters

class PoiRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!PoiGeofenceManager.isEnabled(applicationContext)) return Result.success()
        val force = inputData.getBoolean(KEY_FORCE, false)
        val ok = runCatching { PoiGeofenceManager.refresh(applicationContext, force) }.getOrDefault(false)
        return if (ok || runAttemptCount >= 2) Result.success() else Result.retry()
    }

    companion object {
        private const val UNIQUE_WORK = "historyday_poi_refresh"
        private const val KEY_FORCE = "force"

        fun enqueue(context: Context, force: Boolean) {
            val request = OneTimeWorkRequestBuilder<PoiRefreshWorker>()
                .setInputData(Data.Builder().putBoolean(KEY_FORCE, force).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
