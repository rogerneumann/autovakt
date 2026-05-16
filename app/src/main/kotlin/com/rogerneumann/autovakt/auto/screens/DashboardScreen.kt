package com.rogerneumann.autovakt.auto.screens

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.view.KeyEvent
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.rogerneumann.autovakt.auto.MultiPaneLayoutManager
import com.rogerneumann.autovakt.auto.render.GaugeRenderer
import com.rogerneumann.autovakt.auto.render.GaugeSlotResolver
import com.rogerneumann.autovakt.auto.render.GaugeTheme
import com.rogerneumann.autovakt.data.LightingManager
import com.rogerneumann.autovakt.data.OBD2Repository
import com.rogerneumann.autovakt.data.AutoVaktLiveData
import com.rogerneumann.autovakt.data.VehicleLayoutManager
import com.rogerneumann.autovakt.media.MediaRemoteManager
import kotlin.math.min
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
    private val vehicleLayoutManager: VehicleLayoutManager,
    private val mediaRemoteManager: MediaRemoteManager
) : Screen(carContext), SurfaceCallback, DefaultLifecycleObserver {

    private val renderer = GaugeRenderer()
    private val layoutManager = MultiPaneLayoutManager(carContext)

    private var lastData: AutoVaktLiveData = AutoVaktLiveData()
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

    private var cachedAppPackage: String? = null
    private var cachedAppLabel:   String? = null

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
            data        = lastData,
            onNewTrip   = { lifecycleScope.launch { repository.startManualTrip() } },
            onCycleView = { invalidate() },
            onPrev      = { mediaRemoteManager.dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) },
            onPlayPause = { mediaRemoteManager.dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) },
            onNext      = { mediaRemoteManager.dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) }
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

    /** Full media: dark background with song info (controls are in the action strip). */
    private fun renderMedia(canvas: Canvas) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        val bg = if (currentTheme == GaugeTheme.LIGHT) Color.WHITE else Color.BLACK
        canvas.drawColor(bg)
        drawMediaSection(canvas, 0f, 0f, w, h)
        drawModeDots(canvas, w, h)
    }

    /**
     * Hybrid: telemetry gauges in upper 50%, media strip in lower 50%.
     * Controls are in the action strip — no canvas buttons needed.
     */
    private fun renderHybrid(canvas: Canvas) {
        val w      = canvas.width.toFloat()
        val h      = canvas.height.toFloat()
        val splitY = h * 0.50f

        val bg = if (currentTheme == GaugeTheme.LIGHT) Color.WHITE else Color.BLACK
        canvas.drawColor(bg)

        // ── Upper half: gauges clipped and scaled to the top 50% ─────────────
        canvas.save()
        canvas.clipRect(0f, 0f, w, splitY)
        val layout      = vehicleLayoutManager.getLayout(layoutKey, carContext, isAA = true)
        val assignments = vehicleLayoutManager.getSlotAssignments(layoutKey)
        val profile     = lastData.vehicleProfile
        val slots       = GaugeSlotResolver.resolve(lastData, assignments, profile, vehicleLayoutManager)
        renderer.draw(canvas, slots, layout, currentTheme, w, splitY)
        canvas.restore()

        // ── Divider ───────────────────────────────────────────────────────────
        dividerPaint.color = currentTheme.accent
        canvas.drawLine(w * 0.05f, splitY, w * 0.95f, splitY, dividerPaint)

        // ── Lower half: media strip ───────────────────────────────────────────
        drawMediaSection(canvas, 0f, splitY, w, h)
        drawModeDots(canvas, w, h)
    }

    private fun drawMediaSection(canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float) {
        val cw = x1 - x0
        val ch = y1 - y0
        val cx = x0 + cw / 2f
        val hasMedia = !lastData.currentSongTitle.isNullOrBlank()
        val refDim = min(cw, ch)
        val textColor = if (currentTheme == GaugeTheme.LIGHT) Color.BLACK else Color.WHITE

        // App icon (tappable via action strip — visual indicator only on canvas)
        overlayPaint.textSize = refDim * 0.20f
        overlayPaint.color = currentTheme.accent
        overlayPaint.alpha = if (hasMedia) 200 else 80
        canvas.drawText("♫", cx, y0 + ch * 0.22f, overlayPaint)

        val appLabel = resolveAppLabel(lastData.activeMediaAppPackage)
        if (!appLabel.isNullOrBlank()) {
            overlayPaint.textSize = refDim * 0.07f
            overlayPaint.alpha = 160
            canvas.drawText(appLabel, cx, y0 + ch * 0.22f + refDim * 0.10f, overlayPaint)
        }

        // Song title
        overlayPaint.color = textColor
        overlayPaint.alpha = if (hasMedia) 255 else 120
        overlayPaint.textSize = refDim * 0.11f
        val title = if (hasMedia) lastData.currentSongTitle!!.take(40) else "No media playing"
        canvas.drawText(title, cx, y0 + ch * 0.52f, overlayPaint)

        // Artist
        if (hasMedia && !lastData.currentSongArtist.isNullOrBlank()) {
            overlayPaint.color = Color.argb(200, 180, 180, 180)
            overlayPaint.alpha = 200
            overlayPaint.textSize = refDim * 0.08f
            canvas.drawText(lastData.currentSongArtist!!.take(40), cx, y0 + ch * 0.63f, overlayPaint)
        }

        overlayPaint.alpha = 255
    }

    private fun drawModeDots(canvas: Canvas, w: Float, h: Float) {
        val cx     = w / 2f
        val dotY   = h - 18f
        val dotR   = 6f
        val dotGap = 20f
        val dotPaint = Paint().apply { isAntiAlias = true }
        DashboardDisplayMode.values().forEachIndexed { i, mode ->
            val dotX = cx + (i - 1) * dotGap
            dotPaint.color = if (mode == displayMode) currentTheme.accent else Color.argb(80, 200, 200, 200)
            canvas.drawCircle(dotX, dotY, dotR, dotPaint)
        }
    }

    private fun resolveAppLabel(pkg: String?): String? {
        if (pkg.isNullOrBlank()) return null
        if (pkg == cachedAppPackage) return cachedAppLabel
        cachedAppPackage = pkg
        cachedAppLabel = try {
            val info = carContext.packageManager.getApplicationInfo(pkg, 0)
            carContext.packageManager.getApplicationLabel(info).toString()
        } catch (e: Exception) { null }
        return cachedAppLabel
    }

    override fun onStart(owner: LifecycleOwner) { /* service lifecycle managed externally */ }
}
