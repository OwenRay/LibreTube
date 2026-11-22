package com.github.libretube.ui.preferences

import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.SwitchPreferenceCompat
import com.github.libretube.R
import com.github.libretube.constants.PreferenceKeys
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
        val autoDownloadAudioOnly =
            findPreference<SwitchPreferenceCompat>(PreferenceKeys.AUTO_DOWNLOAD_AUDIO_ONLY)

        fun updateAutoDownloadPreferencesEnabledState(autoDownloadEnabled: Boolean) {
            autoDownloadWifiOnly?.isEnabled = autoDownloadEnabled
            autoDownloadVideoQuality?.isEnabled =
                autoDownloadEnabled && autoDownloadAudioOnly?.isChecked != true
            autoDownloadAudioQuality?.isEnabled = autoDownloadEnabled
            autoDownloadAudioOnly?.isEnabled = autoDownloadEnabled
        }

        updateAutoDownloadPreferencesEnabledState(autoDownload?.isChecked == true)
        autoDownload?.setOnPreferenceChangeListener { _, newValue ->
            updateAutoDownloadPreferencesEnabledState(newValue == true)
            true
        }
        autoDownloadAudioOnly?.setOnPreferenceChangeListener { _, _ ->
            // the video quality picker is irrelevant for audio only downloads
            autoDownloadVideoQuality?.isEnabled = autoDownload?.isChecked == true &&
                autoDownloadAudioOnly?.isChecked != true
            true
        }
    }
}
