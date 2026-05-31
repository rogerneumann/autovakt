package com.rogerneumann.autovakt.auto.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.rogerneumann.autovakt.data.SlotDisplayType

object GaugeCardRenderer {

    fun accentColorFor(shortName: String): Int = when (shortName) {
        "SOC"             -> Color.parseColor("#00E676")
        "PWR"             -> Color.parseColor("#29B6F6")
        "SPEED"           -> Color.parseColor("#ECEFF1")
        "instantMiPerKwh",
        "averageMiPerKwh" -> Color.parseColor("#69F0AE")
        "instantMpg",
        "averageMpg"      -> Color.parseColor("#FFCC02")
        "BATT_T_MAX",
        "BATT_T_MIN"      -> Color.parseColor("#FF9800")
        "HV_V", "HV_I"   -> Color.parseColor("#CE93D8")
        "RPM"             -> Color.parseColor("#EF9A9A")
        "LOAD"            -> Color.parseColor("#FF9800")
        "BOOST_PSI",
        "FUEL_RATE"       -> Color.parseColor("#29B6F6")
        else              -> Color.WHITE
    }

    /**
     * Renders a square gauge card bitmap.  [size] defaults to 512 px for the
     * CoolWalk media browse grid; pass a smaller value (e.g. 256) when the
     * image will be displayed small (CarApp GridTemplate).
     */
    fun renderCard(slot: GaugeSlot, accentColor: Int, size: Int = 512): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.parseColor("#121212"))
        val cx = size / 2f

        val p = Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        when (slot.displayType) {
            SlotDisplayType.ARC -> drawArcCard(canvas, p, slot, accentColor, cx, size)
            SlotDisplayType.BAR -> drawBarCard(canvas, p, slot, accentColor, cx, size)
            else                -> drawNumericCard(canvas, p, slot, accentColor, cx, size)
        }

        return bmp
    }

    private fun drawArcCard(
        canvas: Canvas, p: Paint,
        slot: GaugeSlot, accentColor: Int,
        cx: Float, size: Int
    ) {
        val fraction = slot.fraction?.coerceIn(0f, 1f) ?: 0f
        val arcStroke = size * 0.09f
        val halfStroke = arcStroke / 2f
        val margin = size * 0.10f
        val rect = RectF(
            margin + halfStroke,
            size * 0.18f + halfStroke,
            size - margin - halfStroke,
            size * 0.90f - halfStroke
        )
        val sweepTotal = 270f
        val startAngle = 135f

        val arcPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = arcStroke
            strokeCap = Paint.Cap.ROUND
            color = Color.parseColor("#2A2A2A")
        }
        canvas.drawArc(rect, startAngle, sweepTotal, false, arcPaint)

        arcPaint.color = accentColor
        if (slot.isBidirectional) {
            // Neutral pointer sits at the top (midpoint of the 270° sweep).
            // Positive fraction (> 0.5) sweeps clockwise; negative counter-clockwise.
            val midAngle = startAngle + sweepTotal / 2f
            val sweep = (fraction - 0.5f) * 2f * sweepTotal
            canvas.drawArc(rect, midAngle, sweep, false, arcPaint)
        } else {
            canvas.drawArc(rect, startAngle, sweepTotal * fraction, false, arcPaint)
        }

        p.textSize = size * 0.094f
        p.color = Color.WHITE
        p.alpha = 140
        canvas.drawText(slot.label, cx, size * 0.14f, p)

        val arcCy = (rect.top + rect.bottom) / 2f
        p.color = accentColor
        p.alpha = 255
        p.textSize = size * 0.274f
        while (p.measureText(slot.value) > size * 0.72f && p.textSize > size * 0.098f) p.textSize -= 2f
        canvas.drawText(slot.value, cx, arcCy + p.textSize * 0.35f, p)

        p.textSize = size * 0.090f
        p.color = Color.WHITE
        p.alpha = 180
        canvas.drawText(slot.unit, cx, arcCy + size * 0.274f * 0.35f + size * 0.114f, p)
    }

    private fun drawBarCard(
        canvas: Canvas, p: Paint,
        slot: GaugeSlot, accentColor: Int,
        cx: Float, size: Int
    ) {
        val fraction = slot.fraction?.coerceIn(0f, 1f) ?: 0f
        val barH = size * 0.11f
        val barY = size * 0.76f
        val margin = size * 0.12f
        val barW = size - margin * 2f
        val radius = barH / 2f

        p.textSize = size * 0.094f
        p.color = Color.WHITE
        p.alpha = 140
        canvas.drawText(slot.label, cx, size * 0.14f, p)

        p.color = accentColor
        p.alpha = 255
        p.textSize = size * 0.313f
        while (p.measureText(slot.value) > size * 0.85f && p.textSize > size * 0.098f) p.textSize -= 2f
        canvas.drawText(slot.value, cx, size * 0.52f, p)

        p.textSize = size * 0.094f
        p.color = Color.WHITE
        p.alpha = 180
        canvas.drawText(slot.unit, cx, size * 0.64f, p)

        val barPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            color = Color.parseColor("#2A2A2A")
        }
        canvas.drawRoundRect(margin, barY, margin + barW, barY + barH, radius, radius, barPaint)

        barPaint.color = accentColor
        if (slot.isBidirectional) {
            val midX = margin + barW / 2f
            val displacement = barW / 2f * ((fraction - 0.5f) * 2f)
            val left  = if (displacement >= 0f) midX else midX + displacement
            val right = if (displacement >= 0f) midX + displacement else midX
            if (right > left + 1f) {
                canvas.drawRoundRect(left, barY, right, barY + barH, radius, radius, barPaint)
            }
            val tickPaint = Paint().apply { color = Color.WHITE; alpha = 100; strokeWidth = 3f; isAntiAlias = true }
            canvas.drawLine(midX, barY - 6f, midX, barY + barH + 6f, tickPaint)
        } else {
            val fillW = barW * fraction
            if (fillW > 1f) canvas.drawRoundRect(margin, barY, margin + fillW, barY + barH, radius, radius, barPaint)
        }
    }

    private fun drawNumericCard(
        canvas: Canvas, p: Paint,
        slot: GaugeSlot, accentColor: Int,
        cx: Float, size: Int
    ) {
        p.textSize = size * 0.102f
        p.color = Color.WHITE
        p.alpha = 140
        canvas.drawText(slot.label, cx, size * 0.28f, p)

        p.color = accentColor
        p.alpha = 255
        p.textSize = size * 0.313f
        while (p.measureText(slot.value) > size * 0.88f && p.textSize > size * 0.117f) p.textSize -= 2f
        canvas.drawText(slot.value, cx, size * 0.62f, p)

        p.textSize = size * 0.110f
        p.color = Color.WHITE
        p.alpha = 180
        canvas.drawText(slot.unit, cx, size * 0.80f, p)
    }
}
