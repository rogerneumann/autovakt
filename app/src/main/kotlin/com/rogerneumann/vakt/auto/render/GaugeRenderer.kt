package com.rogerneumann.vakt.auto.render

import android.graphics.*
import com.rogerneumann.vakt.data.GaugeLayout
import com.rogerneumann.vakt.data.PowertrainType
import com.rogerneumann.vakt.data.SlotDisplayType
import com.rogerneumann.vakt.data.VaktLiveData
import com.rogerneumann.vakt.obd2.ConnectionState
import kotlin.math.min

enum class GaugeZone { ARC, LEFT_METRIC, RIGHT_METRIC, POWER_BAR }
enum class DisplayMode { GAUGES, SPLIT, MEDIA }
enum class GaugeStyle { ARC, GRID, TEXT }

private data class GridCellData(
    val label: String, val value: String, val unit: String,
    val fraction: Float, val color: Int
)

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

    // Set at the top of draw(); referenced by all private helpers (single-thread safe)
    private var t = GaugeTheme.DARK

    // ── Primary slot-based draw (Block 12c) ───────────────────────────────────

    /**
     * Primary draw entry point for slot-based rendering.
     * Routes to the appropriate layout-specific draw path based on [layout].
     */
    fun draw(
        canvas: Canvas,
        slots: List<GaugeSlot>,
        layout: GaugeLayout,
        theme: GaugeTheme,
        w: Float = canvas.width.toFloat(),
        h: Float = canvas.height.toFloat()
    ) {
        t = theme
        textPaint.color = t.text
        canvas.drawColor(t.background)

        when (layout) {
            GaugeLayout.GRID_2   -> drawSlotGrid2(canvas, slots, w, h)
            GaugeLayout.GRID_4   -> drawSlotGrid4(canvas, slots, w, h)
            GaugeLayout.GRID_2x3 -> drawSlotGrid2x3(canvas, slots, w, h)
            GaugeLayout.ARC      -> drawSlotArc(canvas, slots, w, h)
        }

        textPaint.color = t.text
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.alpha = 255
    }

    // ── GRID_2: 2 equal tiles (top/bottom halves) ─────────────────────────────

    private fun drawSlotGrid2(canvas: Canvas, slots: List<GaugeSlot>, w: Float, h: Float) {
        val pad  = 10f
        val cellW = w - pad * 2f
        val cellH = (h - pad * 3f) / 2f

        for (i in 0 until 2) {
            val slot = slots.getOrNull(i) ?: GaugeSlot("--", "--", "")
            val top  = pad + i * (cellH + pad)
            drawSlotTile(canvas, pad, top, pad + cellW, top + cellH, slot, valueSizeFraction = 0.38f)
        }
    }

    // ── GRID_4: 2×2 tiles ─────────────────────────────────────────────────────

    private fun drawSlotGrid4(canvas: Canvas, slots: List<GaugeSlot>, w: Float, h: Float) {
        val pad   = 10f
        val cellW = (w - pad * 3f) / 2f
        val cellH = (h - pad * 3f) / 2f

        for (i in 0 until 4) {
            val slot = slots.getOrNull(i) ?: GaugeSlot("--", "--", "")
            val col  = i % 2
            val row  = i / 2
            val left = pad + col * (cellW + pad)
            val top  = pad + row * (cellH + pad)
            drawSlotTile(canvas, left, top, left + cellW, top + cellH, slot, valueSizeFraction = 0.32f)
        }
    }

    // ── GRID_2x3: 2×3 tiles ───────────────────────────────────────────────────

    private fun drawSlotGrid2x3(canvas: Canvas, slots: List<GaugeSlot>, w: Float, h: Float) {
        val pad   = 8f
        val cellW = (w - pad * 3f) / 2f
        val cellH = (h - pad * 4f) / 3f

        for (i in 0 until 6) {
            val slot = slots.getOrNull(i) ?: GaugeSlot("--", "--", "")
            val col  = i % 2
            val row  = i / 2
            val left = pad + col * (cellW + pad)
            val top  = pad + row * (cellH + pad)
            drawSlotTile(canvas, left, top, left + cellW, top + cellH, slot, valueSizeFraction = 0.28f)
        }
    }

    // ── ARC: arc driven by slot[0], three small readouts from slots[1..3] ─────

    private fun drawSlotArc(canvas: Canvas, slots: List<GaugeSlot>, w: Float, h: Float) {
        val alpha  = 255
        val cx     = w / 2f
        val radius = min(w, h) * 0.38f
        val arcCy  = h * 0.44f
        val narrow = w / h < 1.2f

        // Arc gauge from slot[0]
        val arcSlot = slots.getOrNull(0) ?: GaugeSlot("--", "--", "")
        val fraction = arcSlot.value.toFloatOrNull()?.let { it / 100f }?.coerceIn(0f, 1f) ?: 0f
        val arcColor = Color.CYAN
        drawArcGauge(
            canvas, cx, arcCy, radius, alpha,
            fraction    = fraction,
            color       = arcColor,
            centerLabel = arcSlot.value,
            subLabel    = arcSlot.label
        )

        // Small readouts: slots[1] (left), slots[2] (right), slots[3] (below bar if space)
        val scale   = if (narrow) 0.8f else 1.0f
        val metricY = h * 0.10f

        val leftSlot  = slots.getOrNull(1) ?: GaugeSlot("--", "--", "")
        val rightSlot = slots.getOrNull(2) ?: GaugeSlot("--", "--", "")

        drawMetricBlock(canvas, 20f, metricY, alpha, scale,
            label = leftSlot.label,
            value = leftSlot.value,
            unit  = leftSlot.unit,
            align = Paint.Align.LEFT)

        drawMetricBlock(canvas, w - 20f, metricY, alpha, scale,
            label = rightSlot.label,
            value = rightSlot.value,
            unit  = rightSlot.unit,
            align = Paint.Align.RIGHT)

        // slot[3] as a simple text readout beneath the arc
        val slot3 = slots.getOrNull(3)
        if (slot3 != null && slot3.value != "--") {
            textPaint.color     = t.text
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize  = 22f * scale
            textPaint.alpha     = alpha
            canvas.drawText("${slot3.value} ${slot3.unit}".trim(), cx, h * 0.92f, textPaint)
            textPaint.textSize = 14f * scale
            textPaint.alpha    = (alpha * 0.6f).toInt()
            canvas.drawText(slot3.label, cx, h * 0.87f, textPaint)
        }
    }

    // ── Slot tile primitive ────────────────────────────────────────────────────

    /**
     * Draws a single slot as a rounded-rect card.
     * [valueSizeFraction] controls value text size relative to cell height.
     * Branches on [GaugeSlot.displayType]: NUMERIC (text only), ARC (ring), BAR (horizontal bar).
     */
    private fun drawSlotTile(
        canvas: Canvas,
        left: Float, top: Float, right: Float, bottom: Float,
        slot: GaugeSlot,
        valueSizeFraction: Float = 0.32f
    ) {
        val cw = right - left
        val ch = bottom - top
        val cx = left + cw / 2f

        // Draw card background (shared across all display types)
        fillPaint.color = t.cardBackground
        fillPaint.alpha = 255
        canvas.drawRoundRect(left, top, right, bottom, 12f, 12f, fillPaint)

        when (slot.displayType) {
            SlotDisplayType.NUMERIC -> {
                // Cap text sizes against cell width so tall-narrow tiles don't overflow
                val valueSize = minOf(ch * valueSizeFraction, cw * 0.48f)
                val labelSize = minOf(ch * 0.13f, cw * 0.16f)
                val unitSize  = minOf(ch * 0.12f, cw * 0.15f)

                // Label
                textPaint.color     = t.text
                textPaint.textAlign = Paint.Align.CENTER
                textPaint.textSize  = labelSize
                textPaint.alpha     = (255 * 0.55f).toInt()
                canvas.drawText(slot.label, cx, top + ch * 0.24f, textPaint)

                // Value
                textPaint.textSize = valueSize
                textPaint.alpha    = 255
                canvas.drawText(slot.value, cx, top + ch * 0.60f, textPaint)

                // Unit
                textPaint.textSize = unitSize
                textPaint.alpha    = (255 * 0.65f).toInt()
                canvas.drawText(slot.unit, cx, top + ch * 0.78f, textPaint)

                textPaint.alpha = 255
            }

            SlotDisplayType.ARC -> {
                val frac = slot.fraction ?: 0f
                val ringDiam   = minOf(cw, ch) * 0.6f
                val ringRadius = ringDiam / 2f
                val cx2 = left + cw / 2f
                val cy2 = top  + ch / 2f
                val oval = RectF(cx2 - ringRadius, cy2 - ringRadius, cx2 + ringRadius, cy2 + ringRadius)

                val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style       = Paint.Style.STROKE
                    strokeWidth = 10f
                    color       = Color.GRAY
                    alpha       = 80
                }
                val arcPaintLocal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style       = Paint.Style.STROKE
                    strokeWidth = 10f
                    color       = t.accent
                }
                canvas.drawArc(oval, -135f, 270f, false, trackPaint)
                canvas.drawArc(oval, -135f, 270f * frac, false, arcPaintLocal)

                // Value text inside ring (smaller)
                val arcTextSize = minOf(ch * valueSizeFraction * 0.7f, cw * 0.36f)
                val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize  = arcTextSize
                    color     = t.text
                    textAlign = Paint.Align.CENTER
                    typeface  = Typeface.DEFAULT_BOLD
                }
                canvas.drawText(slot.value, cx2, cy2 + arcTextSize / 3f, valuePaint)

                // Label below ring
                val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize  = minOf(ch * 0.11f, cw * 0.14f)
                    color     = t.text
                    alpha     = (255 * 0.55f).toInt()
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawText(slot.label, cx2, top + ch * 0.92f, labelPaint)
            }

            SlotDisplayType.BAR -> {
                val frac    = slot.fraction ?: 0f
                val pad     = cw * 0.08f
                val barTop    = top  + ch * 0.72f
                val barBottom = top  + ch * 0.88f
                val barLeft   = left + pad
                val barRight  = left + cw - pad
                val barWidth  = barRight - barLeft

                // Background track
                val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.GRAY; alpha = 60; style = Paint.Style.FILL
                }
                canvas.drawRect(barLeft, barTop, barRight, barBottom, trackPaint)

                // Fill color: SOC/Batt inverted (high = green), others normal (high = red)
                val barColor = if (slot.label == "SOC" || slot.label == "Batt") {
                    when {
                        frac > 0.66f -> Color.GREEN
                        frac > 0.33f -> Color.YELLOW
                        else         -> Color.RED
                    }
                } else {
                    when {
                        frac < 0.33f -> Color.GREEN
                        frac < 0.66f -> Color.YELLOW
                        else         -> Color.RED
                    }
                }
                val fillBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = barColor; style = Paint.Style.FILL
                }
                canvas.drawRect(barLeft, barTop, barLeft + barWidth * frac, barBottom, fillBarPaint)

                // Value text above bar
                val valueSize = minOf(ch * valueSizeFraction, cw * 0.48f)
                val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize  = valueSize
                    color     = t.text
                    textAlign = Paint.Align.CENTER
                    typeface  = Typeface.DEFAULT_BOLD
                }
                val textX = left + cw / 2f
                canvas.drawText(slot.value, textX, barTop - 4f, valuePaint)

                // Label at top of cell
                val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize  = minOf(ch * 0.13f, cw * 0.16f)
                    color     = t.text
                    alpha     = (255 * 0.55f).toInt()
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawText(slot.label, textX, top + ch * 0.18f, labelPaint)
            }
        }
    }

    // ── Legacy overload (backward compat for DashboardView phone UI) ──────────

    /**
     * Legacy draw path used by [com.rogerneumann.vakt.ui.DashboardView] for the
     * phone-side swipeable modes (GAUGES / SPLIT / MEDIA) and gesture-driven
     * gauge styles (ARC / GRID / TEXT).
     *
     * This overload is retained for the transition period and will be replaced
     * once all callers have migrated to slot-based rendering.
     */
    fun draw(
        canvas: Canvas,
        data: VaktLiveData,
        displayMode: DisplayMode = DisplayMode.GAUGES,
        gaugeStyle: GaugeStyle = GaugeStyle.ARC,
        highlightZone: GaugeZone? = null,
        showModeLabels: Boolean = false,
        theme: GaugeTheme = GaugeTheme.DARK
    ) {
        t = theme
        textPaint.color = t.text
        canvas.drawColor(t.background)

        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        val isLandscape = w > h

        val gaugeW = when (displayMode) {
            DisplayMode.GAUGES -> w
            DisplayMode.SPLIT  -> if (isLandscape) w * 0.58f else w
            DisplayMode.MEDIA  -> 0f
        }
        val gaugeH = when (displayMode) {
            DisplayMode.GAUGES -> h
            DisplayMode.SPLIT  -> if (isLandscape) h else h * 0.58f
            DisplayMode.MEDIA  -> 0f
        }

        if (gaugeW > 0f && gaugeH > 0f) {
            drawGaugeContent(canvas, gaugeW, gaugeH, data, highlightZone, gaugeStyle)
        }

        if (displayMode == DisplayMode.SPLIT || displayMode == DisplayMode.MEDIA) {
            if (isLandscape) {
                val mediaLeft = if (displayMode == DisplayMode.SPLIT) gaugeW else 0f
                drawMediaCard(canvas, mediaLeft, 0f, w, h, data)
            } else {
                val mediaTop = if (displayMode == DisplayMode.SPLIT) gaugeH else 0f
                drawMediaCard(canvas, 0f, mediaTop, w, h, data)
            }
        }
        drawModeIndicator(canvas, w, h, displayMode, showModeLabels)
    }

    // ── Gauge content dispatcher ───────────────────────────────────────────────

    private fun drawGaugeContent(
        canvas: Canvas, w: Float, h: Float,
        data: VaktLiveData, highlightZone: GaugeZone?, gaugeStyle: GaugeStyle
    ) {
        val dimAlpha = if (data.connectionState is ConnectionState.Disconnected) 80 else 255

        when (gaugeStyle) {
            GaugeStyle.ARC  -> drawArcContent(canvas, w, h, data, dimAlpha, highlightZone)
            GaugeStyle.GRID -> drawGridContent(canvas, w, h, data, dimAlpha)
            GaugeStyle.TEXT -> drawTextContent(canvas, w, h, data, dimAlpha)
        }

        drawStatus(canvas, w - 14f, 16f, data.connectionState, dimAlpha)
    }

    // ── ARC style ─────────────────────────────────────────────────────────────

    private fun drawArcContent(
        canvas: Canvas, w: Float, h: Float,
        data: VaktLiveData, alpha: Int, highlightZone: GaugeZone?
    ) {
        val cx     = w / 2f
        val radius = min(w, h) * 0.38f
        val arcCy  = h * 0.44f
        val narrow = w / h < 1.2f

        when (data.vehicleProfile.powertrain) {
            PowertrainType.EV, PowertrainType.PHEV ->
                drawEvArc(canvas, cx, arcCy, radius, w, h, alpha, narrow, data)
            PowertrainType.ICE_GAS ->
                drawIceGasArc(canvas, cx, arcCy, radius, w, h, alpha, narrow, data)
            PowertrainType.ICE_DIESEL ->
                drawIceDieselArc(canvas, cx, arcCy, radius, w, h, alpha, narrow, data)
            PowertrainType.UNKNOWN ->
                drawUnknownArc(canvas, cx, arcCy, radius, w, h, alpha, data)
        }

        if (highlightZone != null) drawZoneHighlight(canvas, highlightZone, cx, arcCy, radius, w, h)
    }

    private fun drawEvArc(
        canvas: Canvas, cx: Float, arcCy: Float, radius: Float,
        w: Float, h: Float, alpha: Int, narrow: Boolean, data: VaktLiveData
    ) {
        drawArcGauge(canvas, cx, arcCy, radius, alpha,
            fraction    = (data.soc ?: 0f) / 100f,
            color       = getSocColor(data.soc ?: 0f),
            centerLabel = "${(data.soc ?: 0f).toInt()}%",
            subLabel    = "SOC"
        )
        val scale = if (narrow) 0.8f else 1.0f
        val metricY = h * 0.10f
        drawMetricBlock(canvas, 20f, metricY, alpha, scale,
            label = "INST", value = data.instantMiPerKwh?.let { "%.1f".format(it) } ?: "--",
            unit = "mi/kWh", align = Paint.Align.LEFT)
        drawMetricBlock(canvas, w - 20f, metricY, alpha, scale,
            label = "AVG", value = data.averageMiPerKwh?.let { "%.1f".format(it) } ?: "--",
            unit = "mi/kWh", align = Paint.Align.RIGHT)
        drawPowerBar(canvas, cx, h * 0.86f, w * 0.82f, data.powerKw ?: 0f, alpha)
    }

    private fun drawIceGasArc(
        canvas: Canvas, cx: Float, arcCy: Float, radius: Float,
        w: Float, h: Float, alpha: Int, narrow: Boolean, data: VaktLiveData
    ) {
        drawArcGauge(canvas, cx, arcCy, radius, alpha,
            fraction    = (data.engineLoad ?: 0f) / 100f,
            color       = getLoadColor(data.engineLoad ?: 0f),
            centerLabel = "${(data.engineLoad ?: 0f).toInt()}%",
            subLabel    = "LOAD"
        )
        val scale = if (narrow) 0.8f else 1.0f
        val metricY = h * 0.10f
        drawMetricBlock(canvas, 20f, metricY, alpha, scale,
            label = "INST", value = data.instantMpg?.let { "%.0f".format(it) } ?: "--",
            unit = "MPG", align = Paint.Align.LEFT)
        drawMetricBlock(canvas, w - 20f, metricY, alpha, scale,
            label = "AVG", value = data.averageMpg?.let { "%.0f".format(it) } ?: "--",
            unit = "MPG", align = Paint.Align.RIGHT)
        drawFuelRateBar(canvas, cx, h * 0.86f, w * 0.82f, data.fuelRateGph ?: 0f, alpha)
    }

    private fun drawIceDieselArc(
        canvas: Canvas, cx: Float, arcCy: Float, radius: Float,
        w: Float, h: Float, alpha: Int, narrow: Boolean, data: VaktLiveData
    ) {
        val boostPsi      = data.boostPressurePsi ?: 0f
        val boostFraction = ((boostPsi + 15f) / 45f).coerceIn(0f, 1f)
        drawArcGauge(canvas, cx, arcCy, radius, alpha,
            fraction    = boostFraction,
            color       = getBoostColor(boostPsi),
            centerLabel = "${boostPsi.toInt()}",
            subLabel    = "PSI BOOST"
        )
        val scale = if (narrow) 0.8f else 1.0f
        val metricY = h * 0.10f
        drawMetricBlock(canvas, 20f, metricY, alpha, scale,
            label = "INST", value = data.instantMpg?.let { "%.0f".format(it) } ?: "--",
            unit = "MPG", align = Paint.Align.LEFT)
        drawMetricBlock(canvas, w - 20f, metricY, alpha, scale,
            label = "AVG", value = data.averageMpg?.let { "%.0f".format(it) } ?: "--",
            unit = "MPG", align = Paint.Align.RIGHT)
        drawFuelRateBar(canvas, cx, h * 0.86f, w * 0.82f, data.fuelRateGph ?: 0f, alpha)
    }

    private fun drawUnknownArc(
        canvas: Canvas, cx: Float, arcCy: Float, radius: Float,
        w: Float, h: Float, alpha: Int, data: VaktLiveData
    ) {
        drawArcGauge(canvas, cx, arcCy, radius, alpha,
            fraction    = (data.soc ?: 0f) / 100f,
            color       = getSocColor(data.soc ?: 0f),
            centerLabel = "${(data.soc ?: 0f).toInt()}%",
            subLabel    = "SOC"
        )
        drawPowerBar(canvas, cx, h * 0.86f, w * 0.82f, data.powerKw ?: 0f, alpha)
    }

    // ── GRID style ────────────────────────────────────────────────────────────

    private fun drawGridContent(
        canvas: Canvas, w: Float, h: Float, data: VaktLiveData, alpha: Int
    ) {
        val pad   = 10f
        val cellW = (w - pad * 3f) / 2f
        val cellH = (h - pad * 3f) / 2f

        val cells: List<GridCellData> = when (data.vehicleProfile.powertrain) {
            PowertrainType.EV, PowertrainType.PHEV, PowertrainType.UNKNOWN -> listOf(
                GridCellData("SOC",     "${(data.soc ?: 0f).toInt()}%",
                    "state of charge", (data.soc ?: 0f) / 100f, getSocColor(data.soc ?: 0f)),
                GridCellData("POWER",   "${(data.powerKw ?: 0f).toInt()}",
                    "kW", ((data.powerKw ?: 0f) + 150f) / 300f, getPowerColor(data.powerKw ?: 0f)),
                GridCellData("INSTANT", data.instantMiPerKwh?.let { "%.1f".format(it) } ?: "--",
                    "mi/kWh", (data.instantMiPerKwh ?: 0f) / 6f, Color.CYAN),
                GridCellData("AVERAGE", data.averageMiPerKwh?.let { "%.1f".format(it) } ?: "--",
                    "mi/kWh", (data.averageMiPerKwh ?: 0f) / 6f, Color.parseColor("#00E676"))
            )
            PowertrainType.ICE_GAS -> listOf(
                GridCellData("LOAD",    "${(data.engineLoad ?: 0f).toInt()}%",
                    "engine load", (data.engineLoad ?: 0f) / 100f, getLoadColor(data.engineLoad ?: 0f)),
                GridCellData("FUEL",    "%.2f".format(data.fuelRateGph ?: 0f),
                    "gal/hr", (data.fuelRateGph ?: 0f) / 2f, getFuelRateColor(data.fuelRateGph ?: 0f)),
                GridCellData("INSTANT", data.instantMpg?.let { "%.0f".format(it) } ?: "--",
                    "MPG", (data.instantMpg ?: 0f) / 50f, Color.CYAN),
                GridCellData("AVERAGE", data.averageMpg?.let { "%.0f".format(it) } ?: "--",
                    "MPG", (data.averageMpg ?: 0f) / 50f, Color.parseColor("#00E676"))
            )
            PowertrainType.ICE_DIESEL -> {
                val boostPsi = data.boostPressurePsi ?: 0f
                listOf(
                    GridCellData("BOOST",   "${boostPsi.toInt()}",
                        "PSI", ((boostPsi + 15f) / 45f).coerceIn(0f, 1f), getBoostColor(boostPsi)),
                    GridCellData("FUEL",    "%.2f".format(data.fuelRateGph ?: 0f),
                        "gal/hr", (data.fuelRateGph ?: 0f) / 2f, getFuelRateColor(data.fuelRateGph ?: 0f)),
                    GridCellData("INSTANT", data.instantMpg?.let { "%.0f".format(it) } ?: "--",
                        "MPG", (data.instantMpg ?: 0f) / 50f, Color.CYAN),
                    GridCellData("AVERAGE", data.averageMpg?.let { "%.0f".format(it) } ?: "--",
                        "MPG", (data.averageMpg ?: 0f) / 50f, Color.parseColor("#00E676"))
                )
            }
        }

        cells.forEachIndexed { i, cell ->
            val col  = i % 2
            val row  = i / 2
            val left = pad + col * (cellW + pad)
            val top  = pad + row * (cellH + pad)
            drawGridCell(canvas, left, top, left + cellW, top + cellH, cell, alpha)
        }
    }

    // ── TEXT style ────────────────────────────────────────────────────────────

    private fun drawTextContent(
        canvas: Canvas, w: Float, h: Float, data: VaktLiveData, alpha: Int
    ) {
        data class Row(val label: String, val value: String, val unit: String)

        val cx = w / 2f

        val primary: String
        val sub: String
        val rows: List<Row>

        when (data.vehicleProfile.powertrain) {
            PowertrainType.EV, PowertrainType.PHEV, PowertrainType.UNKNOWN -> {
                primary = "${(data.soc ?: 0f).toInt()}%"
                sub     = "STATE OF CHARGE"
                rows    = listOf(
                    Row("INSTANT", data.instantMiPerKwh?.let { "%.1f".format(it) } ?: "--", "mi/kWh"),
                    Row("AVERAGE", data.averageMiPerKwh?.let { "%.1f".format(it) } ?: "--", "mi/kWh"),
                    Row("POWER",   "${(data.powerKw ?: 0f).toInt()}", "kW")
                )
            }
            PowertrainType.ICE_GAS -> {
                primary = "${(data.engineLoad ?: 0f).toInt()}%"
                sub     = "ENGINE LOAD"
                rows    = listOf(
                    Row("INSTANT", data.instantMpg?.let { "%.0f".format(it) } ?: "--", "MPG"),
                    Row("AVERAGE", data.averageMpg?.let { "%.0f".format(it) } ?: "--", "MPG"),
                    Row("FUEL",    "%.2f".format(data.fuelRateGph ?: 0f), "GPH")
                )
            }
            PowertrainType.ICE_DIESEL -> {
                primary = "${(data.boostPressurePsi ?: 0f).toInt()}"
                sub     = "PSI BOOST"
                rows    = listOf(
                    Row("INSTANT", data.instantMpg?.let { "%.0f".format(it) } ?: "--", "MPG"),
                    Row("AVERAGE", data.averageMpg?.let { "%.0f".format(it) } ?: "--", "MPG"),
                    Row("FUEL",    "%.2f".format(data.fuelRateGph ?: 0f), "GPH")
                )
            }
        }

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize  = h * 0.26f
        textPaint.alpha     = alpha
        canvas.drawText(primary, cx, h * 0.36f, textPaint)

        textPaint.textSize = h * 0.055f
        textPaint.alpha    = (alpha * 0.55f).toInt()
        canvas.drawText(sub, cx, h * 0.47f, textPaint)

        rows.forEachIndexed { i, row ->
            val y = h * 0.60f + i * h * 0.13f

            textPaint.textAlign = Paint.Align.LEFT
            textPaint.textSize  = h * 0.046f
            textPaint.alpha     = (alpha * 0.50f).toInt()
            canvas.drawText(row.label, w * 0.10f, y, textPaint)

            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize  = h * 0.080f
            textPaint.alpha     = alpha
            canvas.drawText(row.value, cx, y, textPaint)

            textPaint.textAlign = Paint.Align.RIGHT
            textPaint.textSize  = h * 0.046f
            textPaint.alpha     = (alpha * 0.60f).toInt()
            canvas.drawText(row.unit, w * 0.90f, y, textPaint)
        }

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color     = t.text
        textPaint.alpha     = 255
    }

    // ── Media card ────────────────────────────────────────────────────────────

    private fun drawMediaCard(
        canvas: Canvas,
        left: Float, top: Float, right: Float, bottom: Float,
        data: VaktLiveData
    ) {
        val cardW = right - left
        val cardH = bottom - top
        val cx    = left + cardW / 2f
        val cy    = top  + cardH / 2f

        fillPaint.color = t.cardBackground
        fillPaint.alpha = 255
        canvas.drawRect(left, top, right, bottom, fillPaint)

        val dividerPaint = Paint().apply {
            color       = t.accent
            strokeWidth = 2f
            isAntiAlias = true
        }
        if (cardH > cardW) {
            canvas.drawLine(left + cardW * 0.05f, top + 1f, right - cardW * 0.05f, top + 1f, dividerPaint)
        } else {
            canvas.drawLine(left + 1f, top + cardH * 0.05f, left + 1f, bottom - cardH * 0.05f, dividerPaint)
        }

        val hasMedia = !data.currentSongTitle.isNullOrBlank()
        val refDim   = minOf(cardW, cardH)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color     = t.accent
        textPaint.alpha     = if (hasMedia) 255 else 100
        textPaint.textSize  = refDim * 0.22f
        canvas.drawText("♫", cx, cy - refDim * 0.16f, textPaint)

        textPaint.color    = t.text
        textPaint.alpha    = if (hasMedia) 255 else 120
        textPaint.textSize = refDim * 0.13f
        val title = if (hasMedia) data.currentSongTitle!! else "No media playing"
        canvas.drawText(title.take(40), cx, cy + refDim * 0.06f, textPaint)

        textPaint.color    = t.textSecondary
        textPaint.alpha    = if (hasMedia) 200 else 80
        textPaint.textSize = refDim * 0.10f
        val artist = data.currentSongArtist ?: ""
        if (artist.isNotBlank()) canvas.drawText(artist.take(40), cx, cy + refDim * 0.22f, textPaint)

        textPaint.color = t.text
        textPaint.alpha = 255
    }

    // ── Mode indicator ────────────────────────────────────────────────────────

    private fun drawModeIndicator(
        canvas: Canvas, w: Float, h: Float,
        mode: DisplayMode, showLabels: Boolean
    ) {
        val modes    = DisplayMode.values()
        val dotPaint = Paint().apply { isAntiAlias = true }

        if (showLabels) {
            val labels = listOf("gauge", "split", "music")
            val pillW  = 60f
            val pillH  = 24f
            val gap    = 8f
            val totalW = labels.size * pillW + (labels.size - 1) * gap
            val startX = w / 2f - totalW / 2f
            val pillY  = h - 42f

            labels.forEachIndexed { i, label ->
                val left     = startX + i * (pillW + gap)
                val isActive = modes[i] == mode
                dotPaint.style = Paint.Style.FILL
                dotPaint.color = if (isActive) t.pillActiveBg else t.pillInactiveBg
                canvas.drawRoundRect(left, pillY, left + pillW, pillY + pillH, 12f, 12f, dotPaint)

                textPaint.textAlign = Paint.Align.CENTER
                textPaint.textSize  = 11f
                textPaint.color     = if (isActive) t.pillActiveFg else t.pillInactiveFg
                textPaint.alpha     = 255
                canvas.drawText(label, left + pillW / 2f, pillY + pillH * 0.72f, textPaint)
            }
        } else {
            val activeR   = 7f
            val inactiveR = 4f
            val spacing   = 22f
            val startX    = w / 2f - spacing
            val dotY      = h - 22f

            modes.forEachIndexed { i, m ->
                val x        = startX + i * spacing
                val isActive = m == mode
                if (isActive) {
                    dotPaint.style = Paint.Style.FILL
                    dotPaint.color = t.dotActive
                    canvas.drawCircle(x, dotY, activeR, dotPaint)
                } else {
                    dotPaint.style       = Paint.Style.STROKE
                    dotPaint.strokeWidth = 1.5f
                    dotPaint.color       = t.dotInactive
                    canvas.drawCircle(x, dotY, inactiveR, dotPaint)
                }
            }
        }

        textPaint.color     = t.text
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.alpha     = 255
    }

    // ── Zone highlight overlay ────────────────────────────────────────────────

    private fun drawZoneHighlight(
        canvas: Canvas, zone: GaugeZone,
        cx: Float, arcCy: Float, radius: Float, w: Float, h: Float
    ) {
        val paint = Paint().apply {
            style       = Paint.Style.STROKE
            color       = t.dotActive
            strokeWidth = 3f
            alpha       = 200
            isAntiAlias = true
        }
        val metricY = h * 0.10f
        when (zone) {
            GaugeZone.ARC -> {
                val r = radius + 14f
                canvas.drawOval(cx - r, arcCy - r, cx + r, arcCy + r, paint)
            }
            GaugeZone.LEFT_METRIC ->
                canvas.drawRoundRect(4f, metricY - 14f, w * 0.40f, metricY + 80f, 10f, 10f, paint)
            GaugeZone.RIGHT_METRIC ->
                canvas.drawRoundRect(w * 0.60f, metricY - 14f, w - 4f, metricY + 80f, 10f, 10f, paint)
            GaugeZone.POWER_BAR -> {
                val barY  = h * 0.86f
                val halfW = w * 0.82f / 2f
                canvas.drawRect(cx - halfW - 5f, barY - 5f, cx + halfW + 5f, barY + 33f, paint)
            }
        }
    }

    // ── Drawing primitives ────────────────────────────────────────────────────

    private fun drawArcGauge(
        canvas: Canvas, cx: Float, cy: Float, radius: Float, alpha: Int,
        fraction: Float, color: Int, centerLabel: String, subLabel: String
    ) {
        val rect        = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        val strokeWidth = radius * 0.10f

        arcPaint.alpha       = (alpha * 0.35f).toInt()
        arcPaint.color       = t.arcTrack
        arcPaint.strokeWidth = strokeWidth
        canvas.drawArc(rect, 135f, 270f, false, arcPaint)

        arcPaint.alpha = alpha
        arcPaint.color = color
        canvas.drawArc(rect, 135f, fraction * 270f, false, arcPaint)

        textPaint.color     = t.text
        textPaint.alpha     = alpha
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize  = radius * 0.50f
        canvas.drawText(centerLabel, cx, cy + textPaint.textSize * 0.33f, textPaint)

        textPaint.textSize = radius * 0.14f
        textPaint.alpha    = (alpha * 0.75f).toInt()
        canvas.drawText(subLabel, cx, cy + radius * 0.55f, textPaint)
    }

    private fun drawMetricBlock(
        canvas: Canvas, x: Float, y: Float, alpha: Int, scale: Float,
        label: String, value: String, unit: String, align: Paint.Align
    ) {
        textPaint.color     = t.text
        textPaint.textAlign = align
        textPaint.textSize  = 18f * scale
        textPaint.alpha     = (alpha * 0.55f).toInt()
        canvas.drawText(label, x, y, textPaint)

        textPaint.textSize = 38f * scale
        textPaint.alpha    = alpha
        canvas.drawText(value, x, y + 44f * scale, textPaint)

        textPaint.textSize = 16f * scale
        textPaint.alpha    = (alpha * 0.65f).toInt()
        canvas.drawText(unit, x, y + 64f * scale, textPaint)

        textPaint.textAlign = Paint.Align.CENTER
    }

    private fun drawGridCell(
        canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float,
        cell: GridCellData, alpha: Int
    ) {
        val cw = right - left
        val ch = bottom - top
        val cx = left + cw / 2f

        fillPaint.color = t.cardBackground
        fillPaint.alpha = alpha
        canvas.drawRoundRect(left, top, right, bottom, 12f, 12f, fillPaint)

        textPaint.color     = t.text
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize  = ch * 0.13f
        textPaint.alpha     = (alpha * 0.55f).toInt()
        canvas.drawText(cell.label, cx, top + ch * 0.24f, textPaint)

        textPaint.textSize = ch * 0.38f
        textPaint.alpha    = alpha
        canvas.drawText(cell.value, cx, top + ch * 0.62f, textPaint)

        textPaint.textSize = ch * 0.12f
        textPaint.alpha    = (alpha * 0.65f).toInt()
        canvas.drawText(cell.unit, cx, top + ch * 0.78f, textPaint)

        val barH   = 3f
        val barPad = 10f
        val barY   = bottom - barH - 8f
        fillPaint.color = t.gridBarTrack
        fillPaint.alpha = alpha
        canvas.drawRoundRect(left + barPad, barY, right - barPad, barY + barH, 1.5f, 1.5f, fillPaint)
        fillPaint.color = cell.color
        val fill = (cw - barPad * 2f) * cell.fraction.coerceIn(0f, 1f)
        canvas.drawRoundRect(left + barPad, barY, left + barPad + fill, barY + barH, 1.5f, 1.5f, fillPaint)

        textPaint.alpha = 255
    }

    private fun drawPowerBar(
        canvas: Canvas, cx: Float, y: Float, width: Float, powerKw: Float, alpha: Int
    ) {
        val barH  = 28f
        val halfW = width / 2f

        fillPaint.alpha = (alpha * 0.35f).toInt()
        fillPaint.color = t.arcTrack
        canvas.drawRect(cx - halfW, y, cx + halfW, y + barH, fillPaint)

        val ratio = (powerKw / 150f).coerceIn(-1f, 1f)
        val endX  = cx + ratio * halfW
        fillPaint.alpha = alpha
        fillPaint.color = getPowerColor(powerKw)
        if (powerKw < 0f) canvas.drawRect(endX, y, cx, y + barH, fillPaint)
        else              canvas.drawRect(cx,  y, endX, y + barH, fillPaint)

        textPaint.color     = t.text
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize  = 22f
        textPaint.alpha     = alpha
        canvas.drawText("${powerKw.toInt()} kW", cx, y + barH + 28f, textPaint)
    }

    private fun drawFuelRateBar(
        canvas: Canvas, cx: Float, y: Float, width: Float, gph: Float, alpha: Int
    ) {
        val barH  = 28f
        val halfW = width / 2f
        val leftX = cx - halfW

        fillPaint.alpha = (alpha * 0.35f).toInt()
        fillPaint.color = t.arcTrack
        canvas.drawRect(leftX, y, cx + halfW, y + barH, fillPaint)

        val ratio = (gph / 2f).coerceIn(0f, 1f)
        fillPaint.alpha = alpha
        fillPaint.color = getFuelRateColor(gph)
        canvas.drawRect(leftX, y, leftX + ratio * width, y + barH, fillPaint)

        textPaint.color     = t.text
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize  = 22f
        textPaint.alpha     = alpha
        canvas.drawText("%.2f GPH".format(gph), cx, y + barH + 28f, textPaint)
    }

    private fun drawStatus(canvas: Canvas, x: Float, y: Float, state: ConnectionState, alpha: Int) {
        fillPaint.alpha = alpha
        fillPaint.color = when (state) {
            is ConnectionState.Connected  -> Color.GREEN
            is ConnectionState.Connecting -> Color.YELLOW
            else                          -> Color.RED
        }
        canvas.drawCircle(x, y, 6f, fillPaint)
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

    private fun getPowerColor(kw: Float) = when {
        kw < 0f   -> Color.GREEN
        kw > 100f -> Color.RED
        else      -> Color.YELLOW
    }

    private fun getFuelRateColor(gph: Float) = when {
        gph > 1.5f -> Color.RED
        gph > 0.8f -> Color.YELLOW
        else       -> Color.GREEN
    }
}
