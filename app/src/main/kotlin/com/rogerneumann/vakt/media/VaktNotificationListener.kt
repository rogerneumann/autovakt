package com.rogerneumann.vakt.media

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Listens for active media sessions from other apps (Spotify, etc.)
 * to provide metadata and remote control capabilities to Vakt.
 */
@AndroidEntryPoint
class VaktNotificationListener : NotificationListenerService() {

    @Inject lateinit var mediaRemoteManager: MediaRemoteManager

    private var mediaSessionManager: MediaSessionManager? = null
    private var activeController: MediaController? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        mediaSessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        updateActiveSession()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        updateActiveSession()
    }

    /**
     * Finds the most relevant active media session and registers a callback.
     */
    private fun updateActiveSession() {
        val controllers = mediaSessionManager?.getActiveSessions(
            ComponentName(this, VaktNotificationListener::class.java)
        )

        // Find the first playing session, or the first one available
        activeController = controllers?.find {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers?.firstOrNull()

        activeController?.registerCallback(object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                super.onMetadataChanged(metadata)
                val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
                val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
                val packageName = activeController?.packageName ?: ""
                mediaRemoteManager.updateMetadata(title, artist, packageName)
            }
        })

        // Emit current metadata immediately if controller already has it
        activeController?.metadata?.let { metadata ->
            val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
            val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
            val packageName = activeController?.packageName ?: ""
            mediaRemoteManager.updateMetadata(title, artist, packageName)
        }
    }

    /**
     * Proxy for media commands (Play/Pause/Skip)
     */
    fun sendCommand(action: MediaAction) {
        when (action) {
            MediaAction.PLAY -> activeController?.transportControls?.play()
            MediaAction.PAUSE -> activeController?.transportControls?.pause()
            MediaAction.NEXT -> activeController?.transportControls?.skipToNext()
            MediaAction.PREVIOUS -> activeController?.transportControls?.skipToPrevious()
        }
    }
}

enum class MediaAction {
    PLAY, PAUSE, NEXT, PREVIOUS
}
