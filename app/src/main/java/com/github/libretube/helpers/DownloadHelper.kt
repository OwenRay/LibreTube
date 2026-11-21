package com.github.libretube.helpers

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import com.github.libretube.R
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.PlaylistsHelper
import com.github.libretube.api.obj.PipedStream
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.IntentData
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.db.obj.DownloadItem
import com.github.libretube.db.obj.DownloadWithItems
import com.github.libretube.enums.FileType
import com.github.libretube.enums.PlaylistType
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.getWhileDigit
import com.github.libretube.extensions.toID
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.parcelable.DownloadData
import com.github.libretube.services.DownloadService
import com.github.libretube.ui.dialogs.DownloadDialog
import com.github.libretube.ui.dialogs.DownloadPlaylistDialog
import com.github.libretube.ui.dialogs.ShareDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div

object DownloadHelper {
    const val VIDEO_DIR = "video"
    const val AUDIO_DIR = "audio"
    const val SUBTITLE_DIR = "subtitle"
    const val THUMBNAIL_DIR = "thumbnail"
    const val PLAYLIST_THUMBNAIL_DIR = "playlist_thumbnail"
    const val DOWNLOAD_CHUNK_SIZE = 8L * 1024
    const val DEFAULT_TIMEOUT = 15 * 1000
    const val MAX_CONCURRENT_DOWNLOADS = 6
    private const val VIDEO_MIMETYPE = "video/*"

    fun getDownloadDir(context: Context, path: String): Path {
        val storageDir =
            try {
                context.getExternalFilesDir(null)!!
            } catch (e: Exception) {
                context.filesDir
            }
        return (storageDir.toPath() / path).createDirectories()
    }

    fun startDownloadService(context: Context, downloadData: DownloadData? = null) {
        val intent = Intent(context, DownloadService::class.java)
            .putExtra(IntentData.downloadData, downloadData)

        ContextCompat.startForegroundService(context, intent)
    }

    fun DownloadItem.getNotificationId(): Int {
        return Int.MAX_VALUE - id
    }

    fun startDownloadDialog(context: Context, fragmentManager: FragmentManager, videoId: String) {
        val externalProviderPackageName =
            PreferenceHelper.getString(PreferenceKeys.EXTERNAL_DOWNLOAD_PROVIDER, "")

        if (externalProviderPackageName.isBlank()) {
            DownloadDialog().apply {
                arguments = bundleOf(IntentData.videoId to videoId)
            }.show(fragmentManager, DownloadDialog::class.java.name)
        } else {
            val intent = Intent(Intent.ACTION_VIEW)
                .setPackage(externalProviderPackageName)
                .setDataAndType(
                    "${ShareDialog.YOUTUBE_FRONTEND_URL}/watch?v=$videoId".toUri(),
                    VIDEO_MIMETYPE
                )

            runCatching { context.startActivity(intent) }
        }
    }

    fun startDownloadPlaylistDialog(
        context: Context,
        fragmentManager: FragmentManager,
        playlistId: String,
        playlistName: String,
        playlistType: PlaylistType
    ) {
        val externalProviderPackageName =
            PreferenceHelper.getString(PreferenceKeys.EXTERNAL_DOWNLOAD_PROVIDER, "")

        if (externalProviderPackageName.isBlank()) {
            val downloadPlaylistDialog = DownloadPlaylistDialog().apply {
                arguments = bundleOf(
                    IntentData.playlistId to playlistId,
                    IntentData.playlistName to playlistName,
                    IntentData.playlistType to playlistType
                )
            }
            downloadPlaylistDialog.show(fragmentManager, null)
        } else if (playlistType == PlaylistType.PUBLIC) {
            val intent = Intent(Intent.ACTION_VIEW)
                .setPackage(externalProviderPackageName)
                .setDataAndType(
                    "${ShareDialog.YOUTUBE_FRONTEND_URL}/playlist?list=$playlistId".toUri(),
                    VIDEO_MIMETYPE
                )

            runCatching { context.startActivity(intent) }
        } else {
            CoroutineScope(Dispatchers.IO).launch {
                val playlistVideoIds = try {
                    PlaylistsHelper.getPlaylist(playlistId, PlaylistsHelper.getPlaylistType(playlistId))
                } catch (e: Exception) {
                    context.toastFromMainDispatcher(R.string.unknown_error)
                    return@launch
                }.relatedStreams.mapNotNull { it.url?.toID() }.joinToString(",")

                val intent = Intent(Intent.ACTION_VIEW)
                    .setPackage(externalProviderPackageName)
                    .setDataAndType(
                        "${ShareDialog.YOUTUBE_FRONTEND_URL}/watch_videos?video_ids=${playlistVideoIds}".toUri(),
                        VIDEO_MIMETYPE
                    )

                withContext(Dispatchers.Main) {
                    runCatching { context.startActivity(intent) }
                }
            }
        }
    }

    fun extractDownloadInfoText(context: Context, download: DownloadWithItems): List<String> {
        val downloadInfo = mutableListOf<String>()
        download.downloadItems.firstOrNull { it.type == FileType.VIDEO }?.let { videoItem ->
            downloadInfo.add(context.getString(R.string.video) + ": ${videoItem.format} ${videoItem.quality}")
        }
        download.downloadItems.firstOrNull { it.type == FileType.AUDIO }?.let { audioItem ->
            var infoString = ": ${audioItem.quality} ${audioItem.format})"
            if (audioItem.language != null) infoString += " ${audioItem.language}"
            downloadInfo.add(context.getString(R.string.audio) + infoString)
        }
        download.downloadItems.firstOrNull { it.type == FileType.SUBTITLE }?.let {
            downloadInfo.add(context.getString(R.string.captions) + ": ${it.language}")
        }
        return downloadInfo
    }

    suspend fun deleteDownloadIncludingFiles(downloadWithItems: DownloadWithItems) {
        val download = downloadWithItems.download
        val items = downloadWithItems.downloadItems

        items.forEach {
            it.path.deleteIfExists()
        }
        runCatching {
            download.thumbnailPath?.deleteIfExists()
        }

        withContext(Dispatchers.IO) {
            DatabaseHolder.Database.downloadDao().deleteDownload(download)
        }
    }

    private val enqueueMutex = Mutex()
    private val enqueueInFlight = mutableSetOf<String>()

    /**
     * Automatically download a video with the quality selected in the auto download settings.
     * Videos that are already downloaded (or currently downloading) are skipped.
     *
     * @return true if the download was enqueued
     */
    suspend fun enqueueAutoDownload(context: Context, videoId: String): Boolean =
        enqueueMutex.withLock {
            if (!PreferenceHelper.getBoolean(PreferenceKeys.AUTO_DOWNLOAD, false)) return false

            // skip videos that are already downloaded or currently downloading
            if (DatabaseHolder.Database.downloadDao().exists(videoId)) return false

            // the row is only inserted after the service fetched the streams again, so guard
            // the check-then-act window with an in-flight set
            if (enqueueInFlight.contains(videoId)) return false
            enqueueInFlight.add(videoId)
            try {
            // respect the external downloader preference used by all manual download paths
            if (PreferenceHelper.getString(PreferenceKeys.EXTERNAL_DOWNLOAD_PROVIDER, "")
                    .isNotBlank()
            ) {
                return false
            }

            // don't use the user's mobile data unless explicitly allowed
            if (PreferenceHelper.getBoolean(PreferenceKeys.AUTO_DOWNLOAD_WIFI_ONLY, true) &&
                NetworkHelper.isNetworkMetered(context)
            ) {
                return false
            }

            return enqueueAutoDownloadStreams(context, videoId)
        } finally {
            enqueueInFlight.remove(videoId)
        }
    }

    private suspend fun enqueueAutoDownloadStreams(context: Context, videoId: String): Boolean {
        val streams = try {
            MediaServiceRepository.instance.getStreams(videoId)
        } catch (e: Exception) {
            Log.e(TAG(), e.stackTraceToString())
            return false
        }

        // upcoming and live streams can't be downloaded and would only waste bandwidth
        if (streams.isLive) return false

        val videoStream = getBestMatchingStream(
            streams.videoStreams.filter {
                !it.url.isNullOrEmpty() && !it.format.orEmpty().contains("HLS")
            },
            PreferenceHelper.getString(PreferenceKeys.AUTO_DOWNLOAD_VIDEO_QUALITY, "1080p")
        )
        val audioStream = getBestAudioStream(
            streams.audioStreams.filter { !it.url.isNullOrEmpty() },
            PreferenceHelper.getString(PreferenceKeys.AUTO_DOWNLOAD_AUDIO_QUALITY, "128kbps")
        )

        if (videoStream == null && audioStream == null) return false

        return runCatching {
            startDownloadService(
                context,
                DownloadData(
                    videoId = videoId,
                    videoFormat = videoStream?.format,
                    videoQuality = videoStream?.quality,
                    audioFormat = audioStream?.format,
                    audioQuality = audioStream?.quality,
                    audioLanguage = audioStream?.audioTrackLocale,
                    subtitleCode = null
                )
            )
        }.isSuccess
        }

    /**
     * Enqueue downloads for all not yet downloaded videos of the subscriptions feed.
     * Videos that fail (e.g. live streams or temporary errors) are skipped and will
     * be retried on the next feed refresh.
     */
    suspend fun autoDownloadFeedItems(context: Context, feedItems: List<StreamItem>) {
        if (!PreferenceHelper.getBoolean(PreferenceKeys.AUTO_DOWNLOAD, false)) return

        val videoIds = feedItems.mapNotNull { it.url?.toID() }.distinct()
        val downloadedIds = DatabaseHolder.Database.downloadDao()
            .getDownloadedVideoIds(videoIds)
            .toHashSet()

        // enqueue sequentially to keep the amount of parallel API requests sane
        videoIds
            .filterNot { it in downloadedIds }
            .forEach { enqueueAutoDownload(context, it) }
    }

    /**
     * Returns the stream with the highest quality that does not exceed [preferredQuality].
     * An empty [preferredQuality] (automatic) returns the highest quality available. If no
     * stream is below the preferred quality, the lowest quality stream is returned.
     */
    private fun getBestMatchingStream(
        streams: List<PipedStream>,
        preferredQuality: String
    ): PipedStream? {
        if (streams.isEmpty()) return null

        val sortedStreams = streams.sortedBy { it.quality.getWhileDigit() }
        val preferredQualityInt = preferredQuality.getWhileDigit() ?: return sortedStreams.last()

        return sortedStreams.lastOrNull { (it.quality.getWhileDigit() ?: 0) <= preferredQualityInt }
            ?: sortedStreams.firstOrNull()
    }

    /**
     * Returns the highest quality audio stream that does not exceed [preferredQuality],
     * preferring the main audio track (original language) over dubbed or descriptive tracks
     * like the download dialog does.
     */
    private fun getBestAudioStream(
        streams: List<PipedStream>,
        preferredQuality: String
    ): PipedStream? {
        val bestQuality = getBestMatchingStream(streams, preferredQuality) ?: return null

        return streams
            .filter { it.quality.getWhileDigit() == bestQuality.quality.getWhileDigit() }
            .minByOrNull { PlayerHelper.getFullAudioRoleFlags(0, it.audioTrackType.orEmpty()) }
    }
}
