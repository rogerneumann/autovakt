package com.rogerneumann.autovakt.media

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.KeyEvent
import androidx.core.app.NotificationManagerCompat
import com.rogerneumann.autovakt.data.VaktLiveData
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the shared state of the active media session being proxied.
 * Provides formatted data to the UI and Repository consumers, and dispatches
 * media key events to the active player.
 */
@Singleton
class MediaRemoteManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val _mediaState = MutableStateFlow(MediaInfo())
    val mediaState: StateFlow<MediaInfo> = _mediaState.asStateFlow()

    /** Current song title + artist as a pair (title to artist). */
    private val _currentMetadata = MutableStateFlow("" to "")
    val currentMetadata: StateFlow<Pair<String, String>> = _currentMetadata.asStateFlow()

    /** Whether the user has granted Notification Listener access. */
    fun hasNotificationAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /**
     * Called by VaktNotificationListener when metadata changes.
     */
    fun updateMetadata(title: String?, artist: String?, packageName: String?) {
        val t = title ?: ""
        val a = artist ?: ""
        val p = packageName ?: ""
        _mediaState.value = MediaInfo(title = t, artist = a, packageName = p)
        _currentMetadata.value = t to a
    }

    /**
     * Merges current media state into the main telemetry data.
     */
    fun injectInto(liveData: VaktLiveData): VaktLiveData {
        val state = _mediaState.value
        return liveData.copy(
            currentSongTitle = state.title.ifBlank { null },
            currentSongArtist = state.artist.ifBlank { null },
            activeMediaAppPackage = state.packageName
        )
    }

    /**
     * Dispatches a media key event to the active media session.
     * Falls back to AudioManager if no active session is found.
     */
    fun dispatchMediaKey(keyCode: Int) {
        // Attempt via active MediaController
        val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        val cn = ComponentName(context, VaktNotificationListener::class.java)
        val controller = try {
            msm?.getActiveSessions(cn)?.firstOrNull {
                it.playbackState?.state == PlaybackState.STATE_PLAYING
            } ?: msm?.getActiveSessions(cn)?.firstOrNull()
        } catch (e: SecurityException) {
            null
        }

        if (controller != null) {
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY ->
                    controller.transportControls.play()
                KeyEvent.KEYCODE_MEDIA_PAUSE ->
                    controller.transportControls.pause()
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    val state = controller.playbackState?.state
                    if (state == PlaybackState.STATE_PLAYING)
                        controller.transportControls.pause()
                    else
                        controller.transportControls.play()
                }
                KeyEvent.KEYCODE_MEDIA_NEXT ->
                    controller.transportControls.skipToNext()
                KeyEvent.KEYCODE_MEDIA_PREVIOUS ->
                    controller.transportControls.skipToPrevious()
            }
            return
        }

        // Fallback: dispatch via AudioManager
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }
}

data class MediaInfo(
    val title: String = "",
    val artist: String = "",
    val packageName: String = ""
)
