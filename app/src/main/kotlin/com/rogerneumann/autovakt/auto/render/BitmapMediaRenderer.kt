package com.rogerneumann.autovakt.auto.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.rogerneumann.autovakt.data.AutoVaktLiveData
import com.rogerneumann.autovakt.data.GaugeLayout
import com.rogerneumann.autovakt.data.VehicleLayoutManager
import com.rogerneumann.autovakt.data.VehicleProfile

/**
 * Renders a composite 800×480 bitmap for the Android Auto CoolWalk media
 * mini-player slot.
 *
 * Layout when music IS playing (currentSongTitle is non-blank):
 *   - Top 55% (800×264): 2×2 gauge grid drawn via [GaugeRenderer] + [GaugeSlotResolver]
 *   - Bottom 45% (800×216): dark music info strip — song title + artist
 *
 * Layout when NO music is playing:
 *   - Full 800×480: gauge grid only
 *
 * This object is stateless and thread-safe; call [render] directly from any
 * coroutine without DI.
 */
object BitmapMediaRenderer {

    private val renderer = GaugeRenderer()

    /**
     * Renders the composite gauge + music bitmap for the AA media slot.
     *
     * @param data            Latest telemetry + media state (media fields populated by MediaRemoteManager)
     * @param theme           Active [GaugeTheme] from [LightingManager.themeForAA]
     * @param assignments     Slot short-name list from [VehicleLayoutManager.getSlotAssignments]
     * @param profile         Active [VehicleProfile] (from data.vehicleProfile)
     * @param vehicleLayoutManager  Needed by [GaugeSlotResolver] for display type / range enrichment
     * @param width           Bitmap width in pixels (default 800)
     * @param height          Bitmap height in pixels (default 480)
     * @return                Freshly allocated [Bitmap.Config.ARGB_8888] bitmap
     */
    fun render(
        data: AutoVaktLiveData,
        theme: GaugeTheme,
        assignments: List<String?>,
        profile: VehicleProfile,
        vehicleLayoutManager: VehicleLayoutManager,
        width: Int = 800,
        height: Int = 480,
        into: Bitmap? = null
    ): Bitmap {
        val bitmap = if (into != null && into.width == width && into.height == height) {
            into.eraseColor(0)
            into
        } else {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        val canvas = Canvas(bitmap)

        val slots = GaugeSlotResolver.resolve(data, assignments, profile, vehicleLayoutManager)

        val musicPlaying = !data.currentSongTitle.isNullOrBlank()

        if (musicPlaying) {
            val gaugeHeight = height * 0.55f
            val stripTop    = gaugeHeight

            // Top 55%: gauge grid clipped to upper band
            canvas.save()
            canvas.clipRect(0f, 0f, width.toFloat(), gaugeHeight)
            renderer.draw(canvas, slots, GaugeLayout.GRID_4, theme, width.toFloat(), gaugeHeight)
            canvas.restore()

            // Bottom 45%: music info strip
            drawMusicStrip(
                canvas = canvas,
                theme  = theme,
                title  = data.currentSongTitle ?: "",
                artist = data.currentSongArtist ?: "",
                x0     = 0f,
                y0     = stripTop,
                x1     = width.toFloat(),
                y1     = height.toFloat()
            )
        } else {
            // No music — full height gauges
            renderer.draw(canvas, slots, GaugeLayout.GRID_4, theme, width.toFloat(), height.toFloat())
        }

        return bitmap
    }

    // ── Music strip ───────────────────────────────────────────────────────────

    /**
     * Draws the bottom music info strip.  No canvas playback buttons —
     * the AA mini-player provides transport controls natively.
     */
    private fun drawMusicStrip(
        canvas: Canvas,
        theme: GaugeTheme,
        title: String,
        artist: String,
        x0: Float, y0: Float,
        x1: Float, y1: Float
    ) {
        val cw = x1 - x0
        val ch = y1 - y0
        val cx = x0 + cw / 2f

        // Background fill (same color as gauge area background)
        val bgPaint = Paint().apply {
            style = Paint.Style.FILL
            color = theme.background
            isAntiAlias = false
        }
        canvas.drawRect(x0, y0, x1, y1, bgPaint)

        // Accent divider line at the top of the strip
        val divPaint = Paint().apply {
            color       = theme.accent
            strokeWidth = 1.5f
            isAntiAlias = true
        }
        canvas.drawLine(x0 + cw * 0.05f, y0 + 1f, x1 - cw * 0.05f, y0 + 1f, divPaint)

        // Text color: always white on dark backgrounds; use theme.text for light themes
        val textColor = theme.text

        val titlePaint = Paint().apply {
            isAntiAlias = true
            textAlign   = Paint.Align.CENTER
            typeface    = Typeface.DEFAULT_BOLD
            color       = textColor
            textSize    = ch * 0.28f
        }

        val artistPaint = Paint().apply {
            isAntiAlias = true
            textAlign   = Paint.Align.CENTER
            typeface    = Typeface.DEFAULT
            color       = theme.textSecondary
            textSize    = ch * 0.18f
        }

        // Song title — vertically centered in the upper 60% of the strip
        val titleY = y0 + ch * 0.45f
        canvas.drawText(title.take(45), cx, titleY, titlePaint)

        // Artist — below title
        if (artist.isNotBlank()) {
            val artistY = y0 + ch * 0.75f
            canvas.drawText(artist.take(45), cx, artistY, artistPaint)
        }
    }
}
