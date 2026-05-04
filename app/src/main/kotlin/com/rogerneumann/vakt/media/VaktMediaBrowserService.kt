package com.rogerneumann.vakt.media

import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import com.rogerneumann.vakt.data.VaktLiveData
import dagger.hilt.android.AndroidEntryPoint

/**
 * The "Media App Hack" service that allows Vakt to appear in the Android Auto 
 * media slot and Coolwalk 1/3 slot.
 */
@AndroidEntryPoint
class VaktMediaBrowserService : MediaBrowserServiceCompat() {

    private var mediaSession: MediaSessionCompat? = null
    private val rootId = "vakt_root"

    override fun onCreate() {
        super.onCreate()

        // 1. Initialize Media Session
        mediaSession = MediaSessionCompat(this, "VaktMediaSession").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            
            // Required for AA to show the app as an active source
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1.0f)
                    .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or 
                               PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
                    .build()
            )
            
            isActive = true
        }
        
        sessionToken = mediaSession?.sessionToken
    }

    /**
     * Updates the metadata displayed in the AA 1/3 slot or mini-player.
     */
    fun updateMetadata(data: VaktLiveData) {
        val metadataBuilder = MediaMetadataCompat.Builder()
        
        // Adaptive display based on powertrain
        val titleText = when (data.vehicleProfile.powertrain) {
            com.rogerneumann.vakt.data.PowertrainType.EV -> "SOC: ${data.soc?.toInt() ?: "--"}% | ${data.powerKw?.toInt() ?: "--"} kW"
            com.rogerneumann.vakt.data.PowertrainType.ICE_DIESEL -> "Boost: ${data.boostPressurePsi ?: "--"} PSI | ${data.fuelRateGph ?: "--"} GPH"
            else -> "Vakt connected"
        }

        val artistText = if (data.currentSongTitle != null) {
            "${data.currentSongTitle} - ${data.currentSongArtist}"
        } else {
            "No active media"
        }

        metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, titleText)
        metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artistText)
        
        mediaSession?.setMetadata(metadataBuilder.build())
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        // Allow all connections
        return BrowserRoot(rootId, null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        // Return a minimal list so AA knows there is content
        result.sendResult(mutableListOf())
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaSession?.release()
    }
}
