package com.rogerneumann.vakt.media

import com.rogerneumann.vakt.data.VaktLiveData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the shared state of the active media session being proxied.
 * Provides formatted data to the UI and Repository consumers.
 */
@Singleton
class MediaRemoteManager @Inject constructor() {

    private val _mediaState = MutableStateFlow(MediaInfo())
    val mediaState: StateFlow<MediaInfo> = _mediaState.asStateFlow()

    /**
     * Called by the NotificationListener when metadata changes.
     */
    fun updateMetadata(title: String?, artist: String?, packageName: String?) {
        _mediaState.value = MediaInfo(
            title = title ?: "Unknown",
            artist = artist ?: "Unknown",
            packageName = packageName ?: ""
        )
    }

    /**
     * Merges current media state into the main telemetry data.
     */
    fun injectInto(liveData: VaktLiveData): VaktLiveData {
        val state = _mediaState.value
        return liveData.copy(
            currentSongTitle = state.title,
            currentSongArtist = state.artist,
            activeMediaAppPackage = state.packageName
        )
    }
}

data class MediaInfo(
    val title: String = "",
    val artist: String = "",
    val packageName: String = ""
)
