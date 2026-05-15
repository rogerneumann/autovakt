package com.rogerneumann.vakt.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.rogerneumann.vakt.auto.render.DisplayMode
import com.rogerneumann.vakt.auto.render.GaugeRenderer
import com.rogerneumann.vakt.auto.render.GaugeSlot
import com.rogerneumann.vakt.auto.render.GaugeSlotResolver
import com.rogerneumann.vakt.auto.render.GaugeStyle
import com.rogerneumann.vakt.auto.render.GaugeTheme
import com.rogerneumann.vakt.auto.render.GaugeZone
import com.rogerneumann.vakt.data.GaugeLayout
import com.rogerneumann.vakt.data.VaktLiveData
import com.rogerneumann.vakt.data.VehicleLayoutManager
import com.rogerneumann.vakt.data.VehicleProfile
import com.rogerneumann.vakt.util.VaktDisplayState
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Phone-side dashboard view. Delegates all canvas rendering to [GaugeRenderer].
 *
 * Gestures:
 *   Horizontal fling — swipe left/right to cycle GAUGES → SPLIT → MEDIA
 *   Vertical fling   — swipe up/down to cycle gauge style (ARC → GRID → TEXT) when in GAUGES mode
 *   Tap              — show hamburger menu (via click listener set in MainActivity)
 *                      or highlight the tapped gauge zone (ARC style only)
 *   Any touch        — briefly reveals mode labels on the dot indicator
 *
 * Slot-based rendering (Block 12c):
 *   Set [slotAssignments] and [gaugeLayout] to drive slot-based draw paths.
 *   When [gaugeLayout] is non-null the slot-based [GaugeRenderer.draw] overload is used;
 *   otherwise the legacy overload drives the swipe-based styles.
 */
class DashboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val renderer = GaugeRenderer()
    private var data: VaktLiveData = VaktLiveData()
    var theme: GaugeTheme = GaugeTheme.DARK
        set(value) { field = value; postInvalidate() }

    /** Active vehicle profile used for custom PID label/unit resolution. */
    var vehicleProfile: VehicleProfile = VehicleProfile.DEFAULT
        set(value) { field = value; postInvalidate() }

    /** Slot short-name assignments for slot-based rendering. */
    var slotAssignments: List<String?> = listOf(null, null, null, null)
        set(value) { field = value; postInvalidate() }

    /** Layout enum controlling which draw path is used. */
    var gaugeLayout: GaugeLayout = GaugeLayout.GRID_4
        set(value) { field = value; postInvalidate() }

    /** VehicleLayoutManager for per-slot display type and min/max resolution. Injected by the host Activity. */
    var vehicleLayoutManager: VehicleLayoutManager? = null
        set(value) { field = value; postInvalidate() }

    var displayMode: DisplayMode = DisplayMode.SPLIT
        private set

    var gaugeStyle: GaugeStyle = GaugeStyle.ARC
        private set

    private var showModeLabels = false
    private val hideModeLabelsRunnable = Runnable {
        showModeLabels = false
        postInvalidate()
    }

    private var highlightedZone: GaugeZone? = null
    private val clearHighlightRunnable = Runnable {
        highlightedZone = null
        postInvalidate()
    }

    private val mediaPaint = Paint().apply {
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        style = Paint.Style.FILL
    }
    private val dividerPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }
    private val dotPaint = Paint().apply { isAntiAlias = true }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        private val SWIPE_VELOCITY = 400f
        private val SWIPE_DISTANCE = 80f

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            val dx = e2.x - (e1?.x ?: e2.x)
            val dy = e2.y - (e1?.y ?: e2.y)

            return when {
                // Vertical fling: cycle gauge style (only when showing the gauge pane)
                abs(dy) > abs(dx)
                        && abs(dy) >= SWIPE_DISTANCE
                        && abs(velocityY) >= SWIPE_VELOCITY
                        && displayMode == DisplayMode.GAUGES -> {
                    val styles  = GaugeStyle.values()
                    val current = styles.indexOf(gaugeStyle)
                    gaugeStyle = if (dy < 0) {
                        styles[(current + 1) % styles.size]               // up   → next
                    } else {
                        styles[(current - 1 + styles.size) % styles.size] // down → prev
                    }
                    postInvalidate()
                    true
                }
                // Horizontal fling: cycle display mode
                abs(dx) >= SWIPE_DISTANCE && abs(velocityX) >= SWIPE_VELOCITY -> {
                    val modes   = DisplayMode.values()
                    val current = modes.indexOf(displayMode)
                    displayMode = if (dx < 0) {
                        modes[(current + 1) % modes.size]
                    } else {
                        modes[(current - 1 + modes.size) % modes.size]
                    }
                    // Sync to VaktDisplayState so VaktMediaBrowserService mirrors phone view
                    VaktDisplayState.displayMode.value = when (displayMode) {
                        DisplayMode.GAUGES -> "TELEMETRY"
                        DisplayMode.SPLIT  -> "HYBRID"
                        DisplayMode.MEDIA  -> "MEDIA"
                    }
                    postInvalidate()
                    true
                }
                else -> false
            }
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            highlightZoneAt(e.x, e.y)
            return false  // let click listener fire for hamburger reveal
        }
    })

    fun updateData(newData: VaktLiveData) {
        data = newData
        // Keep vehicleProfile in sync with live data profile if not overridden externally
        vehicleProfile = newData.vehicleProfile
        postInvalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            showModeLabels = true
            removeCallbacks(hideModeLabelsRunnable)
            postDelayed(hideModeLabelsRunnable, 2000)
            postInvalidate()
        }
        gestureDetector.onTouchEvent(event)
        return super.onTouchEvent(event)  // preserve click listener for hamburger
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val slots = resolveSlots()

        when (displayMode) {
            DisplayMode.GAUGES -> renderer.draw(canvas, slots, gaugeLayout, theme)
            DisplayMode.SPLIT  -> drawSplit(canvas, w, h, slots)
            DisplayMode.MEDIA  -> {
                canvas.drawColor(theme.background)
                drawMediaStrip(canvas, 0f, 0f, w, h)
            }
        }

        drawModeDots(canvas, w, h)
    }

    private fun resolveSlots(): List<GaugeSlot> {
        val lm = vehicleLayoutManager
        return if (lm != null) {
            GaugeSlotResolver.resolve(data, slotAssignments, vehicleProfile, lm)
        } else {
            slotAssignments.map { shortName ->
                if (shortName == null) GaugeSlot("--", "--", "")
                else GaugeSlot(shortName, "--", "")
            }
        }
    }

    private fun drawSplit(canvas: Canvas, w: Float, h: Float, slots: List<GaugeSlot>) {
        canvas.drawColor(theme.background)
        val isLandscape = w > h
        if (isLandscape) {
            val splitX = w * 0.58f
            canvas.save()
            canvas.clipRect(0f, 0f, splitX, h)
            renderer.draw(canvas, slots, gaugeLayout, theme, splitX, h)
            canvas.restore()
            dividerPaint.color = theme.accent
            canvas.drawLine(splitX, h * 0.05f, splitX, h * 0.95f, dividerPaint)
            drawMediaStrip(canvas, splitX, 0f, w, h)
        } else {
            val splitY = h * 0.50f
            canvas.save()
            canvas.clipRect(0f, 0f, w, splitY)
            renderer.draw(canvas, slots, gaugeLayout, theme, w, splitY)
            canvas.restore()
            dividerPaint.color = theme.accent
            canvas.drawLine(w * 0.05f, splitY, w * 0.95f, splitY, dividerPaint)
            drawMediaStrip(canvas, 0f, splitY, w, h)
        }
    }

    private fun drawMediaStrip(canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float) {
        val cw = x1 - x0
        val ch = y1 - y0
        val cx = x0 + cw / 2f
        val cy = y0 + ch / 2f
        val hasMedia = !data.currentSongTitle.isNullOrBlank()
        val refDim = min(cw, ch)

        mediaPaint.textAlign = Paint.Align.CENTER

        // Music note icon
        mediaPaint.color = theme.accent
        mediaPaint.alpha = if (hasMedia) 200 else 80
        mediaPaint.textSize = refDim * 0.20f
        canvas.drawText("♫", cx, cy - refDim * 0.18f, mediaPaint)

        // Song title
        mediaPaint.color = theme.text
        mediaPaint.alpha = if (hasMedia) 255 else 120
        mediaPaint.textSize = refDim * 0.12f
        val title = if (hasMedia) data.currentSongTitle!!.take(45) else "No media playing"
        canvas.drawText(title, cx, cy + refDim * 0.05f, mediaPaint)

        // Artist
        if (hasMedia && !data.currentSongArtist.isNullOrBlank()) {
            mediaPaint.color = theme.textSecondary
            mediaPaint.alpha = 200
            mediaPaint.textSize = refDim * 0.09f
            canvas.drawText(data.currentSongArtist!!.take(45), cx, cy + refDim * 0.20f, mediaPaint)
        }

        mediaPaint.alpha = 255
    }

    private fun drawModeDots(canvas: Canvas, w: Float, h: Float) {
        val dotY = h - 16f
        val dotR = 5f
        val dotGap = 18f
        DisplayMode.values().forEachIndexed { i, mode ->
            val dotX = w / 2f + (i - 1) * dotGap
            dotPaint.color = if (mode == displayMode) theme.dotActive else theme.dotInactive
            canvas.drawCircle(dotX, dotY, dotR, dotPaint)
        }
    }

    private fun highlightZoneAt(x: Float, y: Float) {
        if (gaugeStyle != GaugeStyle.ARC) return   // zone highlights only relevant in ARC style

        val w           = width.toFloat()
        val h           = height.toFloat()
        val isLandscape = w > h

        val gaugeW = when (displayMode) {
            DisplayMode.GAUGES -> w
            DisplayMode.SPLIT  -> if (isLandscape) w * 0.58f else w
            DisplayMode.MEDIA  -> return
        }
        val gaugeH = when (displayMode) {
            DisplayMode.GAUGES -> h
            DisplayMode.SPLIT  -> if (isLandscape) h else h * 0.58f
            DisplayMode.MEDIA  -> return
        }

        if (isLandscape && x > gaugeW) return
        if (!isLandscape && y > gaugeH) return

        val cx     = gaugeW / 2f
        val radius = min(gaugeW, gaugeH) * 0.38f
        val arcCy  = gaugeH * 0.44f

        val zone = when {
            hypot((x - cx).toDouble(), (y - arcCy).toDouble()) < radius * 1.15 -> GaugeZone.ARC
            y > gaugeH * 0.75f                                                  -> GaugeZone.POWER_BAR
            x < gaugeW * 0.35f && y < gaugeH * 0.30f                           -> GaugeZone.LEFT_METRIC
            x > gaugeW * 0.65f && y < gaugeH * 0.30f                           -> GaugeZone.RIGHT_METRIC
            else                                                                -> return
        }

        removeCallbacks(clearHighlightRunnable)
        highlightedZone = zone
        postInvalidate()
        postDelayed(clearHighlightRunnable, 3000)
    }
}
