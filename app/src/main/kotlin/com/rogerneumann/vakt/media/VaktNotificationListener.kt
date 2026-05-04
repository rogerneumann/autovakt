package com.rogerneumann.vakt.media

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import com.rogerneumann.vakt.data.OBD2Repository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Listens for active media sessions from other apps (Spotify, etc.) 
 * to provide metadata and remote control capabilities to Vakt.
 */
@AndroidEntryPoint
class VaktNotificationListener : NotificationListenerService() {

    // Note: In a real Hilt setup, we would inject a manager here. 
    // For now, we'll use a placeholder for the logic.
    
    private var mediaSessionManager: MediaSessionManager? = null
    private var activeController: MediaController? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        mediaSessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        updateActiveSession()
    }

    /**
     * Finds the most relevant active media session.
     */
    private fun updateActiveSession() {
        val controllers = mediaSessionManager?.getActiveSessions(
            ComponentName(this, VaktNotificationListener::class.java)
        )
        
        // Find the first playing session, or the first one available
        activeController = controllers?.find { it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING }
            ?: controllers?.firstOrNull()
            
        activeController?.registerCallback(object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                super.onMetadataChanged(metadata)
                // TODO: Relay title, artist, and package to OBD2Repository/LiveData
            }
        })
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
