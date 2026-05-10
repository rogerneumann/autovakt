package com.rogerneumann.vakt.auto.render

import android.graphics.*
import com.rogerneumann.vakt.data.PowertrainType
import com.rogerneumann.vakt.data.VaktLiveData
import com.rogerneumann.vakt.obd2.ConnectionState
import kotlin.math.min

/**
 * Powertrain-aware canvas renderer for telemetry gauges.
 * Shared between the phone DashboardView and Android Auto DashboardScreen.
 * Signature draw(canvas, data) is stable — callers do not need to change.
 *
 * Layout zones:
 *   Hero arc  — center, upper portion (primary powertrain metric)
 *   Metric 2  — top-left text block (instant efficiency)
 *   Metric 3  — top-right text block (average efficiency)
 *   Metric 4  — full-width bar, lower portion (power / fuel rate)
 *   Status dot — top-right corner
 */
class GaugeRenderer {

    private val arcPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        color = Color.WHITE
    }

    fun draw(canvas: Canvas, data: VaktLiveData) {
        canvas.drawColor(Color.BLACK)

        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        val cx = w / 2f
        val radius = min(w, h) * 0.38f
        val arcCy = h * 0.44f

        // Alpha-dim everything when not connected — visually "greyed out" without layout change
        val dimAlpha = if (data.connectionState is ConnectionState.Disconnected) 80 else 255

        // Aspect ratio guard: reduce side-metric text on near-square displays
        val isNarrow = w / h < 1.2f

        when (data.vehicleProfile.powertrain) {
            PowertrainType.EV, PowertrainType.PHEV ->
                drawEvLayout(canvas, cx, arcCy, radius, w, h, dimAlpha, isNarrow, data)
            PowertrainType.ICE_GAS ->
                drawIceGasLayout(canvas, cx, arcCy, radius, w, h, dimAlpha, isNarrow, data)
            PowertrainType.ICE_DIESEL ->
                drawIceDieselLayout(canvas, cx, arcCy, radius, w, h, dimAlpha, isNarrow, data)
            PowertrainType.UNKNOWN ->
                drawUnknownLayout(canvas, cx, arcCy, radius, w, h, dimAlpha, data)
        }

        drawStatus(canvas, w - 60f, 60f, data.connectionState, dimAlpha)
    }

    // ── EV / PHEV layout ──────────────────────────────────────────────────────

    private fun drawEvLayout(
        canvas: Canvas, cx: Float, arcCy: Float, radius: Float,
        w: Float, h: Float, alpha: Int, narrow: Boolean, data: VaktLiveData
    ) {
        drawArcGauge(
            canvas, cx, arcCy, radius, alpha,
            fraction    = (data.soc ?: 0f) / 100f,
            color       = getSocColor(data.soc ?: 0f),
            centerLabel = "${(data.soc ?: 0f).toInt()}%",
            subLabel    = "SOC"
        )

        val metricScale = if (narrow) 0.8f else 1.0f
        val leftX  = cx * 0.20f
        val rightX = w - cx * 0.20f
        val metricY = arcCy - radius * 0.25f

        drawMetricBlock(canvas, leftX,  metricY, alpha, metricScale,
            label = "INST", value = data.instantMiPerKwh?.let { "%.1f".format(it) } ?: "--",
            unit = "mi/kWh", align = Paint.Align.LEFT)
        drawMetricBlock(canvas, rightX, metricY, alpha, metricScale,
            label = "AVG",  value = data.averageMiPerKwh?.let { "%.1f".format(it) } ?: "--",
            unit = "mi/kWh", align = Paint.Align.RIGHT)

        drawPowerBar(canvas, cx, h * 0.86f, w * 0.82f, data.powerKw ?: 0f, alpha)
    }

    // ── ICE Gas layout ────────────────────────────────────────────────────────

    private fun drawIceGasLayout(
        canvas: Canvas, cx: Float, arcCy: Float, radius: Float,
        w: Float, h: Float, alpha: Int, narrow: Boolean, data: VaktLiveData
    ) {
        drawArcGauge(
            canvas, cx, arcCy, radius, alpha,
            fraction    = (data.engineLoad ?: 0f) / 100f,
            color       = getLoadColor(data.engineLoad ?: 0f),
            centerLabel = "${(data.engineLoad ?: 0f).toInt()}%",
            subLabel    = "LOAD"
        )

        val metricScale = if (narrow) 0.8f else 1.0f
        val leftX  = cx * 0.20f
        val rightX = w - cx * 0.20f
        val metricY = arcCy - radius * 0.25f

        drawMetricBlock(canvas, leftX,  metricY, alpha, metricScale,
            label = "INST", value = data.instantMpg?.let { "%.0f".format(it) } ?: "--",
            unit = "MPG", align = Paint.Align.LEFT)
        drawMetricBlock(canvas, rightX, metricY, alpha, metricScale,
            label = "AVG",  value = data.averageMpg?.let { "%.0f".format(it) } ?: "--",
            unit = "MPG", align = Paint.Align.RIGHT)

        drawFuelRateBar(canvas, cx, h * 0.86f, w * 0.82f, data.fuelRateGph ?: 0f, alpha)
    }

    // ── ICE Diesel layout ─────────────────────────────────────────────────────

    private fun drawIceDieselLayout(
        canvas: Canvas, cx: Float, arcCy: Float, radius: Float,
        w: Float, h: Float, alpha: Int, narrow: Boolean, data: VaktLiveData
    ) {
        // Boost PSI: maps -15..30 PSI → 0..1 arc fraction
        val boostPsi = data.boostPressurePsi ?: 0f
        val boostFraction = ((boostPsi + 15f) / 45f).coerceIn(0f, 1f)
        drawArcGauge(
            canvas, cx, arcCy, radius, alpha,
            fraction    = boostFraction,
            color       = getBoostColor(boostPsi),
            centerLabel = "${boostPsi.toInt()}",
            subLabel    = "PSI BOOST"
        )

        val metricScale = if (narrow) 0.8f else 1.0f
        val leftX  = cx * 0.20f
        val rightX = w - cx * 0.20f
        val metricY = arcCy - radius * 0.25f

        drawMetricBlock(canvas, leftX,  metricY, alpha, metricScale,
            label = "INST", value = data.instantMpg?.let { "%.0f".format(it) } ?: "--",
            unit = "MPG", align = Paint.Align.LEFT)
        drawMetricBlock(canvas, rightX, metricY, alpha, metricScale,
            label = "AVG",  value = data.averageMpg?.let { "%.0f".format(it) } ?: "--",
            unit = "MPG", align = Paint.Align.RIGHT)

        drawFuelRateBar(canvas, cx, h * 0.86f, w * 0.82f, data.fuelRateGph ?: 0f, alpha)
    }

    // ── Unknown powertrain fallback (current behaviour) ───────────────────────

    private fun drawUnknownLayout(
        canvas: Canvas, cx: Float, arcCy: Float, radius: Float,
        w: Float, h: Float, alpha: Int, data: VaktLiveData
    ) {
        drawArcGauge(
            canvas, cx, arcCy, radius, alpha,
            fraction    = (data.soc ?: 0f) / 100f,
            color       = getSocColor(data.soc ?: 0f),
            centerLabel = "${(data.soc ?: 0f).toInt()}%",
            subLabel    = "SOC"
        )
        drawPowerBar(canvas, cx, h * 0.86f, w * 0.82f, data.powerKw ?: 0f, alpha)
    }

    // ── Drawing primitives ────────────────────────────────────────────────────

    private fun drawArcGauge(
        canvas: Canvas, cx: Float, cy: Float, radius: Float, alpha: Int,
        fraction: Float, color: Int, centerLabel: String, subLabel: String
    ) {
        val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        val strokeWidth = radius * 0.10f

        // Background track
        arcPaint.alpha = (alpha * 0.35f).toInt()
        arcPaint.color = Color.DKGRAY
        arcPaint.strokeWidth = strokeWidth
        canvas.drawArc(rect, 135f, 270f, false, arcPaint)

        // Progress arc
        arcPaint.alpha = alpha
        arcPaint.color = color
        canvas.drawArc(rect, 135f, fraction * 270f, false, arcPaint)

        // Center value
        textPaint.alpha = alpha
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = radius * 0.50f
        canvas.drawText(centerLabel, cx, cy + textPaint.textSize * 0.33f, textPaint)

        // Sub-label below center
        textPaint.textSize = radius * 0.14f
        textPaint.alpha = (alpha * 0.75f).toInt()
        canvas.drawText(subLabel, cx, cy + radius * 0.55f, textPaint)
    }

    private fun drawMetricBlock(
        canvas: Canvas, x: Float, y: Float, alpha: Int, scale: Float,
        label: String, value: String, unit: String, align: Paint.Align
    ) {
        textPaint.textAlign = align

        textPaint.textSize = 18f * scale
        textPaint.alpha = (alpha * 0.55f).toInt()
        canvas.drawText(label, x, y, textPaint)

        textPaint.textSize = 38f * scale
        textPaint.alpha = alpha
        canvas.drawText(value, x, y + 44f * scale, textPaint)

        textPaint.textSize = 16f * scale
        textPaint.alpha = (alpha * 0.65f).toInt()
        canvas.drawText(unit, x, y + 64f * scale, textPaint)

        // Reset align
        textPaint.textAlign = Paint.Align.CENTER
    }

    private fun drawPowerBar(
        canvas: Canvas, cx: Float, y: Float, width: Float, powerKw: Float, alpha: Int
    ) {
        val barH = 28f
        val halfW = width / 2f

        // Background
        fillPaint.alpha = (alpha * 0.35f).toInt()
        fillPaint.color = Color.DKGRAY
        canvas.drawRect(cx - halfW, y, cx + halfW, y + barH, fillPaint)

        // Power fill
        val ratio = (powerKw / 150f).coerceIn(-1f, 1f)
        val endX  = cx + ratio * halfW
        fillPaint.alpha = alpha
        fillPaint.color = when {
            powerKw < 0f   -> Color.GREEN
            powerKw > 100f -> Color.RED
            else           -> Color.YELLOW
        }
        if (powerKw < 0f) canvas.drawRect(endX, y, cx, y + barH, fillPaint)
        else              canvas.drawRect(cx, y, endX, y + barH, fillPaint)

        // Label
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 22f
        textPaint.alpha = alpha
        canvas.drawText("${powerKw.toInt()} kW", cx, y + barH + 28f, textPaint)
    }

    private fun drawFuelRateBar(
        canvas: Canvas, cx: Float, y: Float, width: Float, gph: Float, alpha: Int
    ) {
        val barH = 28f
        val halfW = width / 2f
        val leftX = cx - halfW

        // Background
        fillPaint.alpha = (alpha * 0.35f).toInt()
        fillPaint.color = Color.DKGRAY
        canvas.drawRect(leftX, y, cx + halfW, y + barH, fillPaint)

        // Fuel rate fill (0–2 GPH scale)
        val ratio = (gph / 2f).coerceIn(0f, 1f)
        fillPaint.alpha = alpha
        fillPaint.color = when {
            gph > 1.5f -> Color.RED
            gph > 0.8f -> Color.YELLOW
            else       -> Color.GREEN
        }
        canvas.drawRect(leftX, y, leftX + ratio * width, y + barH, fillPaint)

        // Label
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 22f
        textPaint.alpha = alpha
        canvas.drawText("%.2f GPH".format(gph), cx, y + barH + 28f, textPaint)
    }

    private fun drawStatus(canvas: Canvas, x: Float, y: Float, state: ConnectionState, alpha: Int) {
        fillPaint.alpha = alpha
        fillPaint.color = when (state) {
            is ConnectionState.Connected  -> Color.GREEN
            is ConnectionState.Connecting -> Color.YELLOW
            else                          -> Color.RED
        }
        canvas.drawCircle(x, y, 10f, fillPaint)
    }

    // ── Color helpers ─────────────────────────────────────────────────────────

    private fun getSocColor(soc: Float) = when {
        soc > 20f -> Color.CYAN
        soc > 10f -> Color.YELLOW
        else      -> Color.RED
    }

    private fun getLoadColor(load: Float) = when {
        load > 80f -> Color.RED
        load > 50f -> Color.YELLOW
        else       -> Color.GREEN
    }

    private fun getBoostColor(psi: Float) = when {
        psi > 20f -> Color.RED
        psi > 10f -> Color.YELLOW
        else      -> Color.CYAN
    }
}
