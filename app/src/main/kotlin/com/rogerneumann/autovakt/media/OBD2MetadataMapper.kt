package com.rogerneumann.autovakt.media

import android.graphics.*
import com.rogerneumann.autovakt.data.AutoVaktLiveData

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
     */
    fun generateEvBitmap(data: AutoVaktLiveData): Bitmap {
        val bitmap = Bitmap.createBitmap(gaugeSize, gaugeSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(backgroundColor)
        
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 100f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        
        val soc = data.soc?.toInt() ?: 0
        canvas.drawText("$soc%", gaugeSize / 2f, gaugeSize / 2f + 20f, textPaint)
        
        textPaint.textSize = 40f
        canvas.drawText("${data.powerKw?.toInt() ?: 0} kW", gaugeSize / 2f, gaugeSize / 2f + 80f, textPaint)
        
        val arcPaint = Paint().apply {
            color = if (soc > 20) heroColor else Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 15f
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }
        val rect = RectF(30f, 30f, gaugeSize - 30f, gaugeSize - 30f)
        canvas.drawArc(rect, 135f, (soc / 100f) * 270f, false, arcPaint)
        
        return bitmap
    }

    /**
     * Generates a Bitmap focusing on Battery Temps and Health.
     */
    fun generateBatteryBitmap(data: AutoVaktLiveData): Bitmap {
        val bitmap = Bitmap.createBitmap(gaugeSize, gaugeSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(backgroundColor)
        
        val paint = Paint().apply {
            color = Color.WHITE
            textSize = 50f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        
        canvas.drawText("BATT TEMP", gaugeSize / 2f, 100f, paint)
        
        paint.textSize = 80f
        canvas.drawText("${data.battTempMaxC?.toInt() ?: "--"}°C", gaugeSize / 2f, gaugeSize / 2f + 20f, paint)
        
        paint.textSize = 30f
        paint.color = Color.LTGRAY
        canvas.drawText("Min: ${data.battTempMinC?.toInt() ?: "--"}°C", gaugeSize / 2f, gaugeSize / 2f + 80f, paint)
        
        return bitmap
    }

    /**
     * Generates a Bitmap for Trip Summary.
     */
    fun generateTripBitmap(data: AutoVaktLiveData): Bitmap {
        val bitmap = Bitmap.createBitmap(gaugeSize, gaugeSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(backgroundColor)
        
        val paint = Paint().apply {
            color = Color.WHITE
            textSize = 40f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        
        canvas.drawText("TRIP DISTANCE", gaugeSize / 2f, 80f, paint)
        paint.textSize = 70f
        canvas.drawText("%.1f mi".format(data.tripDistanceMiles ?: 0f), gaugeSize / 2f, 160f, paint)
        
        paint.textSize = 40f
        canvas.drawText("ENERGY USED", gaugeSize / 2f, 240f, paint)
        paint.textSize = 70f
        canvas.drawText("%.1f kWh".format(data.tripEnergyKwh ?: 0f), gaugeSize / 2f, 320f, paint)
        
        return bitmap
    }
}
