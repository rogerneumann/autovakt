package com.rogerneumann.vakt.auto.render

import android.graphics.*
import androidx.car.app.model.CarColor
import com.rogerneumann.vakt.data.VaktLiveData
import kotlin.math.min

/**
 * Handles the Canvas drawing for the high-performance telemetry gauges.
 * Designed for 60fps rendering with high contrast (WCAG AAA).
 */
class GaugeRenderer {

    private val arcPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    fun draw(canvas: Canvas, data: VaktLiveData) {
        canvas.drawColor(Color.BLACK) // Dark mode background for OLED

        val width = canvas.width.toFloat()
        val height = canvas.height.toFloat()
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = min(width, height) * 0.4f

        // 1. Draw SOC Arc (Top)
        drawSocArc(canvas, centerX, centerY, radius, data.soc ?: 0f)

        // 2. Draw Power Bar (Bottom)
        drawPowerBar(canvas, centerX, centerY + radius + 40f, width * 0.8f, data.powerKw ?: 0f)
        
        // 3. Connection Status Indicator
        drawStatus(canvas, width - 60f, 60f, data.connectionState)
    }

    private fun drawSocArc(canvas: Canvas, x: Float, y: Float, radius: Float, soc: Float) {
        val rect = RectF(x - radius, y - radius, x + radius, y + radius)
        
        // Background Arc
        arcPaint.color = Color.DKGRAY
        arcPaint.strokeWidth = 20f
        canvas.drawArc(rect, 135f, 270f, false, arcPaint)

        // Progress Arc
        arcPaint.color = getSocColor(soc)
        canvas.drawArc(rect, 135f, (soc / 100f) * 270f, false, arcPaint)

        // Percentage Text
        textPaint.color = Color.WHITE
        textPaint.textSize = radius * 0.5f
        canvas.drawText("${soc.toInt()}%", x, y + (textPaint.textSize / 3f), textPaint)
        
        textPaint.textSize = radius * 0.15f
        canvas.drawText("SOC", x, y + (textPaint.textSize * 3f), textPaint)
    }

    private fun drawPowerBar(canvas: Canvas, x: Float, y: Float, width: Float, powerKw: Float) {
        val barHeight = 30f
        val halfWidth = width / 2f
        
        // Background
        arcPaint.style = Paint.Style.FILL
        arcPaint.color = Color.DKGRAY
        canvas.drawRect(x - halfWidth, y, x + halfWidth, y + barHeight, arcPaint)

        // Power level (Regen = Green, Consumption = Orange/Red)
        val powerRatio = (powerKw / 150f).coerceIn(-1f, 1f) // Scale to 150kW max
        val progressX = x + (powerRatio * halfWidth)
        
        arcPaint.color = if (powerKw < 0) Color.GREEN else if (powerKw > 100) Color.RED else Color.YELLOW
        if (powerKw < 0) {
            canvas.drawRect(progressX, y, x, y + barHeight, arcPaint)
        } else {
            canvas.drawRect(x, y, progressX, y + barHeight, arcPaint)
        }

        // Labels
        textPaint.textSize = 24f
        textPaint.color = Color.WHITE
        canvas.drawText("${powerKw.toInt()} kW", x, y + barHeight + 35f, textPaint)
    }

    private fun drawStatus(canvas: Canvas, x: Float, y: Float, state: com.rogerneumann.vakt.obd2.ConnectionState) {
        val color = when (state) {
            is com.rogerneumann.vakt.obd2.ConnectionState.Connected -> Color.GREEN
            is com.rogerneumann.vakt.obd2.ConnectionState.Connecting -> Color.YELLOW
            else -> Color.RED
        }
        
        arcPaint.style = Paint.Style.FILL
        arcPaint.color = color
        canvas.drawCircle(x, y, 10f, arcPaint)
    }

    private fun getSocColor(soc: Float): Int {
        return when {
            soc > 20f -> Color.CYAN
            soc > 10f -> Color.YELLOW
            else -> Color.RED
        }
    }
}
