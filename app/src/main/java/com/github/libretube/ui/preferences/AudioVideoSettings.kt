package com.github.libretube.ui.preferences

import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.SwitchPreferenceCompat
import com.github.libretube.R
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.helpers.AutoDownloadScheduler
import com.github.libretube.ui.base.BasePreferenceFragment

class AudioVideoSettings : BasePreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.audio_video_settings, rootKey)

        // the auto download preferences are only relevant when auto download is enabled
        val autoDownload = findPreference<SwitchPreferenceCompat>(PreferenceKeys.AUTO_DOWNLOAD)
        val autoDownloadWifiOnly =
            findPreference<SwitchPreferenceCompat>(PreferenceKeys.AUTO_DOWNLOAD_WIFI_ONLY)
        val autoDownloadVideoQuality =
            findPreference<ListPreference>(PreferenceKeys.AUTO_DOWNLOAD_VIDEO_QUALITY)
        val autoDownloadAudioQuality =
            findPreference<ListPreference>(PreferenceKeys.AUTO_DOWNLOAD_AUDIO_QUALITY)

        fun updateAutoDownloadPreferencesEnabledState(autoDownloadEnabled: Boolean) {
            autoDownloadWifiOnly?.isEnabled = autoDownloadEnabled
            autoDownloadVideoQuality?.isEnabled = autoDownloadEnabled
            autoDownloadAudioQuality?.isEnabled = autoDownloadEnabled
        }

        updateAutoDownloadPreferencesEnabledState(autoDownload?.isChecked == true)
        autoDownload?.setOnPreferenceChangeListener { _, newValue ->
            updateAutoDownloadPreferencesEnabledState(newValue == true)
            // check for new subscription videos right away when the feature is enabled
            if (newValue == true) {
                context?.let { AutoDownloadScheduler.runNow(it) }
            }
            true
        }
    }
}
