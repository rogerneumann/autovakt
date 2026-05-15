package com.rogerneumann.vakt.auto.screens

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.rogerneumann.vakt.auto.MultiPaneLayoutManager
import com.rogerneumann.vakt.auto.render.GaugeRenderer
import com.rogerneumann.vakt.auto.render.GaugeSlotResolver
import com.rogerneumann.vakt.auto.render.GaugeTheme
import com.rogerneumann.vakt.data.LightingManager
import com.rogerneumann.vakt.data.OBD2Repository
import com.rogerneumann.vakt.data.VaktLiveData
import com.rogerneumann.vakt.data.VehicleLayoutManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** View modes for the full-screen AA dashboard — mirrors the phone's DisplayMode concept. */
enum class DashboardDisplayMode { HYBRID, TELEMETRY, MEDIA }

/**
 * The primary full-screen dashboard for Vakt.
 *
 * Implements high-performance Canvas drawing via [SurfaceCallback].
 * Template building is fully delegated to [MultiPaneLayoutManager] (Phase 4B)
 * so the action strip and background color adapt automatically to the
 * head unit's display mode (WIDE / FULL_SCREEN / NARROW).
 *
 * Block 12c: layout and slot assignments are read from [VehicleLayoutManager]
 * each render frame via the global fallback key `"gauge_layout_global"`.
 * Block 12b will replace this with the proper per-VIN / per-profile key.
 *
 * Block 14c: swipe (onFling) switches between HYBRID / TELEMETRY / MEDIA views.
 */
class DashboardScreen(
    carContext: CarContext,
    private val repository: OBD2Repository,
    private val lightingManager: LightingManager,
    private val vehicleLayoutManager: VehicleLayoutManager
) : Screen(carContext), SurfaceCallback, DefaultLifecycleObserver {

    private val renderer = GaugeRenderer()
    private val layoutManager = MultiPaneLayoutManager(carContext)

    private var lastData: VaktLiveData = VaktLiveData()
    private var currentTheme: GaugeTheme = GaugeTheme.DARK
    private var currentSurfaceContainer: SurfaceContainer? = null
    private var displayMode: DashboardDisplayMode = DashboardDisplayMode.HYBRID

    // Hardcoded global key for Block 12c; Block 12b will provide the proper key via
    // VehicleLayoutManager.resolveKey(vin, adapterMac, profileId).
    private val layoutKey = "gauge_layout_global"

    // Paint objects for the media/hybrid overlay drawing
    private val overlayPaint = Paint().apply {
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        color = Color.WHITE
    }
    private val dividerPaint = Paint().apply {
        color = Color.argb(120, 255, 255, 255)
        strokeWidth = 2f
        isAntiAlias = true
    }

    init {
        lifecycle.addObserver(this)
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)

        lifecycleScope.launch {
            repository.liveData.collectLatest { data ->
                lastData = data
                renderFrame()
                invalidate()
            }
        }

        lifecycleScope.launch {
            lightingManager.themeForAA.collectLatest { theme ->
                currentTheme = theme
                renderFrame()
            }
        }
    }

    // ── Template (Phase 4B) ───────────────────────────────────────────────────

    override fun onGetTemplate(): Template {
        return layoutManager.buildTemplate(
            data       = lastData,
            onNewTrip  = {
                lifecycleScope.launch {
                    repository.startManualTrip()
                }
            },
            onCycleView = { invalidate() }
        )
    }

    // ── SurfaceCallback ───────────────────────────────────────────────────────

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        currentSurfaceContainer = surfaceContainer
        renderFrame()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        currentSurfaceContainer = null
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        renderFrame()
    }

    /**
     * Block 14c: fling gesture switches between the 3 dashboard views.
     *   Left fling  (velocityX < -500) → TELEMETRY (data/gauges)
     *   Right fling (velocityX >  500) → MEDIA
     *   Slow/vertical → HYBRID (center default)
     */
    override fun onFling(velocityX: Float, velocityY: Float) {
        val newMode = when {
            velocityX < -500f -> DashboardDisplayMode.TELEMETRY  // left → full data
            velocityX > 500f  -> DashboardDisplayMode.MEDIA       // right → music
            else              -> DashboardDisplayMode.HYBRID
        }
        if (newMode != displayMode) {
            displayMode = newMode
            renderFrame()
            invalidate()
        }
    }

    // ── Canvas Rendering ──────────────────────────────────────────────────────

    /**
     * Locks the surface canvas, resolves layout + slots from [VehicleLayoutManager],
     * delegates drawing to [GaugeRenderer] or overlay painters, then posts.
     * Only called when new data arrives (1–2 Hz) — not on every frame.
     */
    private fun renderFrame() {
        val surface = currentSurfaceContainer?.surface ?: return
        if (!surface.isValid) return
        try {
            val canvas: Canvas = surface.lockCanvas(null)

            when (displayMode) {
                DashboardDisplayMode.TELEMETRY -> renderTelemetry(canvas)
                DashboardDisplayMode.MEDIA     -> renderMedia(canvas)
                DashboardDisplayMode.HYBRID    -> renderHybrid(canvas)
            }

            surface.unlockCanvasAndPost(canvas)
        } catch (_: Exception) { /* surface may have been destroyed */ }
    }

    /** Full telemetry: delegates entirely to GaugeRenderer (same as original). */
    private fun renderTelemetry(canvas: Canvas) {
        val layout      = vehicleLayoutManager.getLayout(layoutKey, carContext, isAA = true)
        val assignments = vehicleLayoutManager.getSlotAssignments(layoutKey)
        val profile     = lastData.vehicleProfile
        val slots = GaugeSlotResolver.resolve(lastData, assignments, profile, vehicleLayoutManager)
        renderer.draw(canvas, slots, layout, currentTheme)
    }

    /** Full media: dark background with centered song + artist text. */
    private fun renderMedia(canvas: Canvas) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        val bg = if (currentTheme == GaugeTheme.LIGHT) Color.WHITE else Color.BLACK
        canvas.drawColor(bg)

        val textColor = if (currentTheme == GaugeTheme.LIGHT) Color.BLACK else Color.WHITE
        val cx = w / 2f
        val cy = h / 2f

        val hasMedia = !lastData.currentSongTitle.isNullOrBlank()

        // Music note icon
        overlayPaint.color  = currentTheme.accent
        overlayPaint.textSize = h * 0.18f
        canvas.drawText("♪", cx, cy - h * 0.18f, overlayPaint)

        // Song title
        overlayPaint.color    = textColor
        overlayPaint.textSize = h * 0.11f
        val title = if (hasMedia) lastData.currentSongTitle!! else "No media playing"
        canvas.drawText(title.take(50), cx, cy + h * 0.02f, overlayPaint)

        // Artist
        if (hasMedia) {
            overlayPaint.color    = Color.argb(200, 180, 180, 180)
            overlayPaint.textSize = h * 0.08f
            val artist = lastData.currentSongArtist ?: ""
            if (artist.isNotBlank()) {
                canvas.drawText(artist.take(50), cx, cy + h * 0.13f, overlayPaint)
            }
        }

        // Skip labels
        overlayPaint.color    = Color.argb(160, 200, 200, 200)
        overlayPaint.textSize = h * 0.07f
        canvas.drawText("◀◀ Prev", cx * 0.38f, h * 0.88f, overlayPaint)
        canvas.drawText("Next ▶▶", cx * 1.62f, h * 0.88f, overlayPaint)
    }

    /**
     * Hybrid: telemetry gauges in upper half, media strip in lower half.
     * Upper half uses GaugeRenderer clipped to top portion; lower half draws text.
     */
    private fun renderHybrid(canvas: Canvas) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        val splitY = h * 0.58f

        val bg = if (currentTheme == GaugeTheme.LIGHT) Color.WHITE else Color.BLACK
        canvas.drawColor(bg)

        // ── Upper half: telemetry via GaugeRenderer into a sub-canvas clip ────
        canvas.save()
        canvas.clipRect(0f, 0f, w, splitY)

        val layout      = vehicleLayoutManager.getLayout(layoutKey, carContext, isAA = true)
        val assignments = vehicleLayoutManager.getSlotAssignments(layoutKey)
        val profile     = lastData.vehicleProfile
        val slots       = GaugeSlotResolver.resolve(lastData, assignments, profile, vehicleLayoutManager)
        renderer.draw(canvas, slots, layout, currentTheme)

        canvas.restore()

        // ── Divider ────────────────────────────────────────────────────────────
        dividerPaint.color = currentTheme.accent
        canvas.drawLine(w * 0.05f, splitY, w * 0.95f, splitY, dividerPaint)

        // ── Lower half: media strip ────────────────────────────────────────────
        val textColor = if (currentTheme == GaugeTheme.LIGHT) Color.BLACK else Color.WHITE
        val cx  = w / 2f
        val mcy = splitY + (h - splitY) / 2f

        val hasMedia = !lastData.currentSongTitle.isNullOrBlank()

        overlayPaint.color    = textColor
        overlayPaint.textSize = (h - splitY) * 0.26f
        val title = if (hasMedia) lastData.currentSongTitle!! else "No media playing"
        canvas.drawText(title.take(50), cx, mcy - (h - splitY) * 0.05f, overlayPaint)

        if (hasMedia) {
            overlayPaint.color    = Color.argb(180, 180, 180, 180)
            overlayPaint.textSize = (h - splitY) * 0.18f
            val artist = lastData.currentSongArtist ?: ""
            if (artist.isNotBlank()) {
                canvas.drawText(artist.take(50), cx, mcy + (h - splitY) * 0.16f, overlayPaint)
            }
        }

        // Mode indicator dots at bottom
        val dotY   = h - 18f
        val dotR   = 6f
        val dotGap = 20f
        val modes  = DashboardDisplayMode.values()
        val dotPaint = Paint().apply { isAntiAlias = true }
        modes.forEachIndexed { i, mode ->
            val dotX = cx + (i - 1) * dotGap
            dotPaint.color = if (mode == displayMode) currentTheme.accent else Color.argb(80, 200, 200, 200)
            canvas.drawCircle(dotX, dotY, dotR, dotPaint)
        }
    }

    override fun onStart(owner: LifecycleOwner) { /* service lifecycle managed externally */ }
}
