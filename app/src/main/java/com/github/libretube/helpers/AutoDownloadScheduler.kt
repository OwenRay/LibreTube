package com.github.libretube.helpers

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.github.libretube.workers.AutoDownloadWorker
import java.util.concurrent.TimeUnit

object AutoDownloadScheduler {
    const val AUTO_DOWNLOAD_WORK_NAME = "auto_download_worker"

    /**
     * Schedule periodic checks for new videos to download. Existing schedules are kept,
     * so this can safely be called on every app start.
     */
    fun enqueuePeriodic(
        context: Context,
        existingPolicy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP
    ) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val worker = PeriodicWorkRequestBuilder<AutoDownloadWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                AUTO_DOWNLOAD_WORK_NAME,
                existingPolicy,
                worker
            )
    }

    /**
     * Run the check once immediately, e.g. when the user enables the feature.
     */
    fun runNow(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val worker = OneTimeWorkRequestBuilder<AutoDownloadWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                AUTO_DOWNLOAD_WORK_NAME + "_now",
                ExistingWorkPolicy.REPLACE,
                worker
            )
    }
}