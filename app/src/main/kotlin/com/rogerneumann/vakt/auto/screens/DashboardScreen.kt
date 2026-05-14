package com.rogerneumann.vakt.auto.screens

import android.graphics.Canvas
import android.graphics.Rect
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
import com.rogerneumann.vakt.auto.render.GaugeTheme
import com.rogerneumann.vakt.data.LightingManager
import com.rogerneumann.vakt.data.OBD2Repository
import com.rogerneumann.vakt.data.VaktLiveData
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The primary full-screen dashboard for Vakt.
 *
 * Implements high-performance Canvas drawing via [SurfaceCallback].
 * Template building is fully delegated to [MultiPaneLayoutManager] (Phase 4B)
 * so the action strip and background color adapt automatically to the
 * head unit's display mode (WIDE / FULL_SCREEN / NARROW).
 */
class DashboardScreen(
    carContext: CarContext,
    private val repository: OBD2Repository,
    private val lightingManager: LightingManager
) : Screen(carContext), SurfaceCallback, DefaultLifecycleObserver {

    private val renderer = GaugeRenderer()
    private val layoutManager = MultiPaneLayoutManager(carContext)

    private var lastData: VaktLiveData = VaktLiveData()
    private var currentTheme: GaugeTheme = GaugeTheme.DARK
    private var currentSurfaceContainer: SurfaceContainer? = null

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

    // ── Canvas Rendering ──────────────────────────────────────────────────────

    /**
     * Locks the surface canvas, delegates drawing to [GaugeRenderer], then
     * posts. Only called when new data arrives (1–2 Hz) — not on every frame.
     */
    private fun renderFrame() {
        val surface = currentSurfaceContainer?.surface ?: return
        if (!surface.isValid) return
        try {
            val canvas: Canvas = surface.lockCanvas(null)
            renderer.draw(canvas, lastData, theme = currentTheme)
            surface.unlockCanvasAndPost(canvas)
        } catch (_: Exception) { /* surface may have been destroyed */ }
    }

    override fun onStart(owner: LifecycleOwner) { /* service lifecycle managed externally */ }
}
