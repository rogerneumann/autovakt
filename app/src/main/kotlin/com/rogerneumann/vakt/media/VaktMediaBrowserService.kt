package com.rogerneumann.vakt.media

import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import com.rogerneumann.vakt.data.OBD2Repository
import com.rogerneumann.vakt.data.VaktLiveData
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The "Media App Hack" service that allows Vakt to appear in the Android Auto 
 * media slot and Coolwalk 1/3 slot.
 * P1 FIX: Now injects OBD2Repository and collects liveData to keep the
 * media session metadata in sync with real (or demo) telemetry.
 */
@AndroidEntryPoint
class VaktMediaBrowserService : MediaBrowserServiceCompat() {

    @Inject lateinit var repository: OBD2Repository

    private var mediaSession: MediaSessionCompat? = null
    private val rootId = "vakt_root"
    private val metadataMapper = OBD2MetadataMapper()
    private var currentViewMode = ViewMode.EV

    // Service-scoped coroutine scope — cancelled in onDestroy.
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    enum class ViewMode { EV, BATTERY, TRIP }

    override fun onCreate() {
        super.onCreate()

        mediaSession = MediaSessionCompat(this, "VaktMediaSession").apply {
            @Suppress("DEPRECATION")
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onSkipToNext() { cycleMode(1) }
                override fun onSkipToPrevious() { cycleMode(-1) }
            })

            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1.0f)
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                    ).build()
            )
            
            isActive = true
        }
        
        sessionToken = mediaSession?.sessionToken

        // P1 FIX: Collect liveData and push to media session so AA mini-player
        // actually reflects telemetry instead of showing blank metadata.
        serviceScope.launch {
            repository.liveData.collectLatest { data ->
                updateMetadata(data)
            }
        }
    }

    private fun cycleMode(delta: Int) {
        val modes = ViewMode.values()
        val nextIndex = (currentViewMode.ordinal + delta + modes.size) % modes.size
        currentViewMode = modes[nextIndex]
        // Trigger a metadata update with last known data if possible, or wait for next poll
    }

    /**
     * Updates the metadata displayed in the AA 1/3 slot or mini-player.
     */
    fun updateMetadata(data: VaktLiveData) {
        val metadataBuilder = MediaMetadataCompat.Builder()
        
        val bitmap = when (currentViewMode) {
            ViewMode.EV -> metadataMapper.generateEvBitmap(data)
            ViewMode.BATTERY -> metadataMapper.generateBatteryBitmap(data)
            ViewMode.TRIP -> metadataMapper.generateTripBitmap(data)
        }

        val titleText = when (currentViewMode) {
            ViewMode.EV -> "SOC: ${data.soc?.toInt() ?: "--"}% | ${data.powerKw?.toInt() ?: "--"} kW"
            ViewMode.BATTERY -> "Temp: ${data.battTempMaxC?.toInt() ?: "--"}°C | Min: ${data.battTempMinC?.toInt() ?: "--"}°C"
            ViewMode.TRIP -> "Dist: %.1f mi | Energy: %.1f kWh".format(data.tripDistanceMiles ?: 0f, data.tripEnergyKwh ?: 0f)
        }

        metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, titleText)
        metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Vakt • ${currentViewMode.name}")
        metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
        
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
        serviceScope.cancel()
        mediaSession?.release()
    }
}
