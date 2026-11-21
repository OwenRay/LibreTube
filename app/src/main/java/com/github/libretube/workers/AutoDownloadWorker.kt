package com.github.libretube.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.github.libretube.api.SubscriptionHelper
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.extensions.TAG
import com.github.libretube.helpers.DownloadHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.repo.FeedRefresh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Periodically checks the subscriptions feed and auto downloads new videos
 * if the auto download setting is enabled.
 */
class AutoDownloadWorker(appContext: Context, parameters: WorkerParameters) :
    CoroutineWorker(appContext, parameters) {

    override suspend fun doWork(): Result {
        // nothing to do if the feature or the automatic downloader is disabled
        if (!PreferenceHelper.getBoolean(PreferenceKeys.AUTO_DOWNLOAD, false)) {
            return Result.success()
        }
        if (PreferenceHelper.getString(PreferenceKeys.EXTERNAL_DOWNLOAD_PROVIDER, "")
                .isNotBlank()
        ) {
            return Result.success()
        }

        return try {
            // refresh the feed and download all new videos, shorts and live streams are skipped
            val feed = withContext(Dispatchers.IO) {
                SubscriptionHelper.getFeed(FeedRefresh.All)
            }.filter { !it.isUpcoming && !it.isShort }

            DownloadHelper.autoDownloadFeedItems(applicationContext, feed)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG(), e.stackTraceToString())
            Result.retry()
        }
    }
}