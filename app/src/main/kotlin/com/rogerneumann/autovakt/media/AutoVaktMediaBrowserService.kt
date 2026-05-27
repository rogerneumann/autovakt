package com.rogerneumann.autovakt.media

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.media.MediaBrowserServiceCompat
import com.rogerneumann.autovakt.R
import com.rogerneumann.autovakt.auto.render.BitmapMediaRenderer
import com.rogerneumann.autovakt.auto.render.GaugeSlotResolver
import com.rogerneumann.autovakt.auto.render.GaugeTheme
import com.rogerneumann.autovakt.data.LightingManager
import com.rogerneumann.autovakt.data.OBD2Repository
import com.rogerneumann.autovakt.data.AutoVaktLiveData
import com.rogerneumann.autovakt.data.VehicleLayoutManager
import com.rogerneumann.autovakt.util.AutoVaktDisplayState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The media service that allows AutoVakt to appear in the Android Auto
 * Coolwalk 1/3 media slot. Renders one of three bitmap layouts:
 *
 *  - HYBRID    : 2×2 telemetry grid (top 55%) + song info (bottom 45%)
 *  - TELEMETRY : full-screen 2×2 telemetry grid
 *  - MEDIA     : full-screen centered song title + artist + control icons
 *
 * The Coolwalk skip buttons ALWAYS control media (never cycle views).
 * View mode is driven by [AutoVaktDisplayState.displayMode] which MainActivity
 * updates whenever the user swipes the phone.
 */
@AndroidEntryPoint
class AutoVaktMediaBrowserService : MediaBrowserServiceCompat() {

    @Inject lateinit var repository: OBD2Repository
    @Inject lateinit var mediaRemoteManager: MediaRemoteManager
    @Inject lateinit var vehicleLayoutManager: VehicleLayoutManager
    @Inject lateinit var lightingManager: LightingManager

    private var mediaSession: MediaSessionCompat? = null
    private val rootId = "autovakt_root"

    // 800×480 landscape canvas — standard AA mini-player size
    private val bitmapW = 800
    private val bitmapH = 480

    // Pre-allocated and reused every render cycle to avoid 1.5 MB GC pressure per frame
    private val renderBitmap by lazy { Bitmap.createBitmap(bitmapW, bitmapH, Bitmap.Config.ARGB_8888) }

    private var currentTheme: GaugeTheme = GaugeTheme.DARK

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()

        mediaSession = MediaSessionCompat(this, "AutoVaktMediaSession").apply {
            @Suppress("DEPRECATION")
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )

            setCallback(object : MediaSessionCompat.Callback() {
                override fun onSkipToNext() {
                    mediaRemoteManager.dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                }
                override fun onSkipToPrevious() {
                    mediaRemoteManager.dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                }
                override fun onPlay() {
                    mediaRemoteManager.dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                }
                override fun onPause() {
                    mediaRemoteManager.dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                }
                override fun onCustomAction(action: String, extras: Bundle?) {
                    when (action) {
                        "CYCLE_VIEW" -> {
                            AutoVaktDisplayState.displayMode.value = when (
                                AutoVaktDisplayState.displayMode.value
                            ) {
                                "HYBRID"    -> "TELEMETRY"
                                "TELEMETRY" -> "MEDIA"
                                else        -> "HYBRID"   // MEDIA → back to HYBRID
                            }
                        }
                        "OPEN_MUSIC_APP" -> {
                            val pkg = repository.liveData.value.activeMediaAppPackage
                            if (!pkg.isNullOrBlank()) {
                                packageManager.getLaunchIntentForPackage(pkg)
                                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    ?.let { startActivity(it) }
                            }
                        }
                    }
                }
            })

            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1.0f)
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                    )
                    .addCustomAction(
                        PlaybackStateCompat.CustomAction.Builder(
                            "CYCLE_VIEW",
                            "Cycle View",
                            R.drawable.ic_grid_view_24
                        ).build()
                    )
                    .addCustomAction(
                        PlaybackStateCompat.CustomAction.Builder(
                            "OPEN_MUSIC_APP",
                            "Open Music App",
                            R.drawable.ic_music_note_24
                        ).build()
                    )
                    .build()
            )

            isActive = true
        }

        sessionToken = mediaSession?.sessionToken

        // Observe liveData, display mode, media metadata, and theme; re-render on any change
        serviceScope.launch {
            combine(
                repository.liveData,
                AutoVaktDisplayState.displayMode,
                mediaRemoteManager.currentMetadata,
                lightingManager.themeForAA
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                Triple(
                    values[0] as AutoVaktLiveData,
                    values[1] as String,
                    values[3] as GaugeTheme
                )
            }
                .collectLatest { (data, mode, theme) ->
                    currentTheme = theme
                    updateBitmap(data, mode)
                }
        }
    }

    /**
     * Renders the appropriate bitmap and pushes it to the MediaSession.
     *
     * TELEMETRY and HYBRID modes are rendered via [BitmapMediaRenderer] which
     * uses [GaugeRenderer] + [GaugeSlotResolver] for the gauge grid and draws
     * the music strip when [AutoVaktLiveData.currentSongTitle] is set.
     * MEDIA mode keeps the existing full-screen media card path.
     */
    private fun updateBitmap(data: AutoVaktLiveData, displayMode: String) {
        val metadata = mediaRemoteManager.currentMetadata.value
        val songTitle = metadata.first
        val songArtist = metadata.second

        val bitmap: Bitmap = when (displayMode) {
            "MEDIA" -> {
                // Full-screen media card — rendered inline (controls not provided by AA strip here)
                renderBitmap.eraseColor(0)
                drawMediaFull(Canvas(renderBitmap), songTitle, songArtist)
                renderBitmap
            }
            else -> {
                // HYBRID (default) and TELEMETRY: delegate to BitmapMediaRenderer.
                // Use the same VIN/MAC/profile-keyed slot assignments that OBD2Repository
                // resolved on connect — not the global key which has no saved slots.
                val assignments = vehicleLayoutManager.getSlotAssignments(repository.currentLayoutKey.value)
                BitmapMediaRenderer.render(
                    data               = data,
                    theme              = currentTheme,
                    assignments        = assignments,
                    profile            = data.vehicleProfile,
                    vehicleLayoutManager = vehicleLayoutManager,
                    width              = bitmapW,
                    height             = bitmapH,
                    into               = renderBitmap
                )
            }
        }

        val titleText = when {
            displayMode == "MEDIA" -> if (songTitle.isNotBlank()) songTitle else "No media playing"
            displayMode == "TELEMETRY" -> buildTelemetryTitle(data)
            else -> buildHybridTitle(data, songTitle)
        }
        val artistText = when {
            displayMode == "MEDIA" -> songArtist
            else -> "AutoVakt"
        }

        val metadataBuilder = MediaMetadataCompat.Builder()
        metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, titleText)
        metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artistText)
        metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
        mediaSession?.setMetadata(metadataBuilder.build())
    }

    // ── Telemetry section helpers ─────────────────────────────────────────────

    private val paint = Paint().apply {
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        color = Color.WHITE
    }
    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private fun drawTelemetryFull(canvas: Canvas, data: AutoVaktLiveData) {
        canvas.drawColor(Color.parseColor("#121212"))
        drawTelemetryGrid(canvas, data, 0f, 0f, bitmapW.toFloat(), bitmapH.toFloat())
    }

    private fun drawHybrid(canvas: Canvas, data: AutoVaktLiveData, title: String, artist: String) {
        canvas.drawColor(Color.parseColor("#121212"))
        val splitY = bitmapH * 0.55f
        drawTelemetryGrid(canvas, data, 0f, 0f, bitmapW.toFloat(), splitY)
        drawMediaStrip(canvas, 0f, splitY, bitmapW.toFloat(), bitmapH.toFloat(), title, artist)
    }

    private fun drawMediaFull(canvas: Canvas, title: String, artist: String) {
        canvas.drawColor(Color.parseColor("#121212"))
        drawMediaStrip(canvas, 0f, 0f, bitmapW.toFloat(), bitmapH.toFloat(), title, artist)
    }

    /**
     * Draws a 2×2 grid of telemetry tiles from the first 4 slot assignments.
     */
    private fun drawTelemetryGrid(
        canvas: Canvas, data: AutoVaktLiveData,
        x0: Float, y0: Float, x1: Float, y1: Float
    ) {
        val defaultSlots = listOf("SOC", "PWR", "SPEED", "BATT_T_MAX")
        val slots = GaugeSlotResolver.resolve(data, defaultSlots, data.vehicleProfile, vehicleLayoutManager)

        val pad = 8f
        val cellW = (x1 - x0 - pad * 3f) / 2f
        val cellH = (y1 - y0 - pad * 3f) / 2f

        for (i in 0 until 4) {
            val slot = slots.getOrNull(i)
            val col = i % 2
            val row = i / 2
            val left = x0 + pad + col * (cellW + pad)
            val top = y0 + pad + row * (cellH + pad)
            drawTile(canvas, left, top, left + cellW, top + cellH,
                slot?.label ?: "--", slot?.value ?: "--", slot?.unit ?: "")
        }
    }

    private fun drawTile(
        canvas: Canvas,
        left: Float, top: Float, right: Float, bottom: Float,
        label: String, value: String, unit: String
    ) {
        val cw = right - left
        val ch = bottom - top
        val cx = left + cw / 2f

        fillPaint.color = Color.parseColor("#1E1E1E")
        fillPaint.alpha = 255
        canvas.drawRoundRect(left, top, right, bottom, 10f, 10f, fillPaint)

        val labelSize = minOf(ch * 0.14f, cw * 0.16f)
        val valueSize = minOf(ch * 0.38f, cw * 0.46f)
        val unitSize = minOf(ch * 0.13f, cw * 0.15f)

        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER

        paint.textSize = labelSize
        paint.alpha = (255 * 0.55f).toInt()
        canvas.drawText(label, cx, top + ch * 0.24f, paint)

        paint.textSize = valueSize
        paint.alpha = 255
        canvas.drawText(value, cx, top + ch * 0.62f, paint)

        paint.textSize = unitSize
        paint.alpha = (255 * 0.65f).toInt()
        canvas.drawText(unit, cx, top + ch * 0.80f, paint)

        paint.alpha = 255
    }

    /**
     * Draws the media strip: song title, artist, and Unicode transport icons.
     * When title is blank, shows "No media playing".
     */
    private fun drawMediaStrip(
        canvas: Canvas,
        x0: Float, y0: Float, x1: Float, y1: Float,
        title: String, artist: String
    ) {
        val cw = x1 - x0
        val ch = y1 - y0
        val cx = x0 + cw / 2f
        val cy = y0 + ch / 2f

        val hasMedia = title.isNotBlank()
        val refDim = minOf(cw, ch)

        // Divider line (if inside hybrid, draw separator)
        if (y0 > 0f) {
            val divPaint = Paint().apply {
                color = Color.parseColor("#00E676")
                strokeWidth = 1.5f
                isAntiAlias = true
            }
            canvas.drawLine(x0 + cw * 0.05f, y0 + 1f, x1 - cw * 0.05f, y0 + 1f, divPaint)
        }

        // Music note icon
        paint.color = Color.parseColor("#00E676")
        paint.alpha = if (hasMedia) 200 else 80
        paint.textSize = refDim * 0.20f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("♫", cx, cy - refDim * 0.18f, paint)

        // Song title
        paint.color = Color.WHITE
        paint.alpha = if (hasMedia) 255 else 120
        paint.textSize = refDim * 0.12f
        val displayTitle = if (hasMedia) title.take(45) else "No media playing"
        canvas.drawText(displayTitle, cx, cy + refDim * 0.05f, paint)

        // Artist
        if (hasMedia && artist.isNotBlank()) {
            paint.color = Color.LTGRAY
            paint.alpha = 200
            paint.textSize = refDim * 0.09f
            canvas.drawText(artist.take(45), cx, cy + refDim * 0.20f, paint)
        }

        // Transport controls: ⏮ ⏸/▶ ⏭
        val ctrlY = y1 - refDim * 0.12f
        paint.color = Color.WHITE
        paint.alpha = if (hasMedia) 230 else 60
        paint.textSize = refDim * 0.14f

        val spacing = refDim * 0.22f
        canvas.drawText("⏮", cx - spacing, ctrlY, paint)  // ⏮ skip back
        canvas.drawText("⏯", cx, ctrlY, paint)            // ⏯ play/pause
        canvas.drawText("⏭", cx + spacing, ctrlY, paint)  // ⏭ skip forward

        paint.alpha = 255
        paint.textAlign = Paint.Align.CENTER
    }

    // ── Metadata title helpers ────────────────────────────────────────────────

    private fun buildTelemetryTitle(data: AutoVaktLiveData): String {
        val soc = data.soc?.toInt()
        val pwr = data.powerKw?.toInt()
        return buildString {
            if (soc != null) append("SOC: $soc%")
            if (pwr != null) {
                if (isNotEmpty()) append(" | ")
                append("$pwr kW")
            }
            if (isEmpty()) append("AutoVakt Telemetry")
        }
    }

    private fun buildHybridTitle(data: AutoVaktLiveData, songTitle: String): String {
        val soc = data.soc?.toInt()
        return when {
            songTitle.isNotBlank() -> songTitle
            soc != null -> "SOC: $soc% | AutoVakt"
            else -> "AutoVakt"
        }
    }

    // ── MediaBrowserServiceCompat overrides ───────────────────────────────────

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        return BrowserRoot(rootId, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        result.sendResult(mutableListOf())
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        mediaSession?.release()
    }
}
