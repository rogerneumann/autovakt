package com.rogerneumann.vakt.auto

import androidx.car.app.CarContext
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import com.rogerneumann.vakt.data.VaktLiveData
import com.rogerneumann.vakt.obd2.ConnectionState

/**
 * Phase 4B: MultiPaneLayoutManager
 *
 * Determines the current display mode based on Car App constraints and
 * provides the correct [Template] for each configuration.
 *
 * Modes:
 * - [DisplayMode.FULL_SCREEN] — user opened Vakt directly; full NavigationTemplate canvas.
 * - [DisplayMode.WIDE] — landscape head unit with extra horizontal space (e.g., 16:9 wide display).
 * - [DisplayMode.NARROW] — portrait or constrained display (smaller head unit).
 *
 * The [DashboardScreen] calls [buildTemplate] on every telemetry update so the
 * correct action strip and color cues are always in sync with the data.
 */
class MultiPaneLayoutManager(private val carContext: CarContext) {

    enum class DisplayMode { FULL_SCREEN, WIDE, NARROW }

    /**
     * Infers the current display mode from Car App's ConstraintManager.
     * Grid content limit is a reliable proxy for available horizontal space.
     */
    fun getCurrentMode(): DisplayMode {
        return try {
            val cm = carContext.getCarService(ConstraintManager::class.java)
            val gridLimit = cm.getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID)
            when {
                gridLimit >= 6 -> DisplayMode.WIDE       // wide landscape displays
                gridLimit >= 4 -> DisplayMode.FULL_SCREEN
                else           -> DisplayMode.NARROW      // compact / portrait
            }
        } catch (e: Exception) {
            DisplayMode.FULL_SCREEN // safe default if service unavailable
        }
    }

    /**
     * Builds the appropriate [NavigationTemplate] for the given [DisplayMode].
     *
     * All modes use the same surface canvas (drawn by [GaugeRenderer]); the
     * difference is the action strip content and background color cues.
     *
     * @param data        Current telemetry snapshot — used to drive status colors.
     * @param onNewTrip   Callback fired when the user taps "New Trip".
     * @param onCycleView Callback fired when the user taps "Cycle View" (next metric set).
     */
    fun buildTemplate(
        data: VaktLiveData,
        onNewTrip: () -> Unit,
        onCycleView: () -> Unit
    ): Template {
        val mode = getCurrentMode()
        return when (mode) {
            DisplayMode.WIDE        -> buildWideTemplate(data, onNewTrip, onCycleView)
            DisplayMode.FULL_SCREEN -> buildFullTemplate(data, onNewTrip, onCycleView)
            DisplayMode.NARROW      -> buildNarrowTemplate(data, onNewTrip)
        }
    }

    // ── Wide display: show both New Trip AND Cycle View actions ──────────────

    private fun buildWideTemplate(
        data: VaktLiveData,
        onNewTrip: () -> Unit,
        onCycleView: () -> Unit
    ): Template = NavigationTemplate.Builder()
        .setActionStrip(
            ActionStrip.Builder()
                .addAction(
                    Action.Builder()
                        .setTitle("Cycle View")
                        .setOnClickListener(onCycleView)
                        .build()
                )
                .addAction(
                    Action.Builder()
                        .setTitle("New Trip")
                        .setOnClickListener(onNewTrip)
                        .build()
                )
                .build()
        )
        .setBackgroundColor(connectionColor(data))
        .build()

    // ── Standard full-screen: primary action + map action strip ──────────────

    private fun buildFullTemplate(
        data: VaktLiveData,
        onNewTrip: () -> Unit,
        onCycleView: () -> Unit
    ): Template = NavigationTemplate.Builder()
        .setActionStrip(
            ActionStrip.Builder()
                .addAction(
                    Action.Builder()
                        .setTitle("New Trip")
                        .setOnClickListener(onNewTrip)
                        .build()
                )
                .build()
        )
        .setMapActionStrip(
            ActionStrip.Builder()
                .addAction(
                    Action.Builder()
                        .setTitle("⟳")
                        .setOnClickListener(onCycleView)
                        .build()
                )
                .build()
        )
        .setBackgroundColor(connectionColor(data))
        .build()

    // ── Narrow display: minimal — only New Trip ───────────────────────────────

    private fun buildNarrowTemplate(
        data: VaktLiveData,
        onNewTrip: () -> Unit
    ): Template = NavigationTemplate.Builder()
        .setActionStrip(
            ActionStrip.Builder()
                .addAction(
                    Action.Builder()
                        .setTitle("New Trip")
                        .setOnClickListener(onNewTrip)
                        .build()
                )
                .build()
        )
        .setBackgroundColor(connectionColor(data))
        .build()

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Maps the connection state to a background color tint so the driver
     * instantly sees connection health without reading text.
     *
     * Connected   → DEFAULT (OLED black from GaugeRenderer)
     * Connecting  → YELLOW  (warning / scanning)
     * Error/Off   → RED     (needs attention)
     */
    private fun connectionColor(data: VaktLiveData): CarColor = when (data.connectionState) {
        is ConnectionState.Connected   -> CarColor.DEFAULT
        is ConnectionState.Connecting  -> CarColor.YELLOW
        else                           -> CarColor.RED
    }
}
