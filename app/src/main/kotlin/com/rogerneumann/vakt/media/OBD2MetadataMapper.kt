package com.rogerneumann.vakt.media

import android.graphics.*
import com.rogerneumann.vakt.data.VaktLiveData

/**
 * Responsible for generating the Bitmaps used in the Android Auto 1/3 slot.
 * Handles the "Painting" of the telemetry gauges.
 */
class OBD2MetadataMapper {

    private val gaugeSize = 400
    private val backgroundColor = Color.parseColor("#121212")
    private val heroColor = Color.parseColor("#00E676") // Standard EV Green

    /**
     * Generates a Bitmap for the EV Telemetry Focus view.
     * Features a large SOC % and a circular power arc.
     */
    fun generateEvBitmap(data: VaktLiveData): Bitmap {
        val bitmap = Bitmap.createBitmap(gaugeSize, gaugeSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // 1. Background
        canvas.drawColor(backgroundColor)
        
        // 2. SOC Text
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 120f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        
        val socText = "${data.soc?.toInt() ?: "--"}%"
        canvas.drawText(socText, gaugeSize / 2f, gaugeSize / 2f + 40f, textPaint)
        
        // 3. Power Arc (Gauge)
        val arcPaint = Paint().apply {
            color = heroColor
            style = Paint.Style.STROKE
            strokeWidth = 20f
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }
        
        val rect = RectF(40f, 40f, gaugeSize - 40f, gaugeSize - 40f)
        val sweepAngle = ((data.soc ?: 0f) / 100f) * 360f
        canvas.drawArc(rect, -90f, sweepAngle, false, arcPaint)
        
        return bitmap
    }
}
