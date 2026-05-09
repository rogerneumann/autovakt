package com.rogerneumann.vakt.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import com.rogerneumann.vakt.auto.render.GaugeRenderer
import com.rogerneumann.vakt.data.VaktLiveData

/**
 * Phone-side dashboard view that mirrors the Android Auto GaugeRenderer.
 *
 * Reuses the same [GaugeRenderer] used in [DashboardScreen] so the phone
 * preview is pixel-identical to what shows on the head unit.
 *
 * Usage: call [updateData] whenever new [VaktLiveData] arrives; the view
 * schedules an [invalidate] which triggers [onDraw] on the next frame.
 */
class DashboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val renderer = GaugeRenderer()
    private var data: VaktLiveData = VaktLiveData()

    /**
     * Push new telemetry data and schedule a redraw.
     * Safe to call from any thread — [postInvalidate] handles threading.
     */
    fun updateData(newData: VaktLiveData) {
        data = newData
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        renderer.draw(canvas, data)
    }
}
