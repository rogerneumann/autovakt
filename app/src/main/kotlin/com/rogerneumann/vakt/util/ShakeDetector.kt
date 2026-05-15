package com.rogerneumann.vakt.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Detects a device shake and fires [onShake] at most once per [DEBOUNCE_MS].
 *
 * Threshold is ~15 m/s² delta per axis combination — firm enough to avoid false
 * positives from road vibration, but reliable from a deliberate wrist shake.
 *
 * Usage:
 *   private val shakeDetector = ShakeDetector(context) { shareLogs() }
 *   override fun onResume()  { shakeDetector.start() }
 *   override fun onPause()   { shakeDetector.stop()  }
 */
class ShakeDetector(context: Context, private val onShake: () -> Unit) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var lastShakeMs  = 0L

    private val listener = object : SensorEventListener {
        private var lastX = 0f; private var lastY = 0f; private var lastZ = 0f
        private var initialized = false

        override fun onSensorChanged(event: SensorEvent) {
            if (!initialized) {
                lastX = event.values[0]; lastY = event.values[1]; lastZ = event.values[2]
                initialized = true
                return
            }
            val dx = event.values[0] - lastX
            val dy = event.values[1] - lastY
            val dz = event.values[2] - lastZ
            lastX = event.values[0]; lastY = event.values[1]; lastZ = event.values[2]

            if (dx * dx + dy * dy + dz * dz > SHAKE_THRESHOLD_SQ) {
                val now = System.currentTimeMillis()
                if (now - lastShakeMs > DEBOUNCE_MS) {
                    lastShakeMs = now
                    onShake()
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun start() {
        sensorManager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
    }

    companion object {
        private const val SHAKE_THRESHOLD_SQ = 225f  // 15 m/s² magnitude squared
        private const val DEBOUNCE_MS        = 3_000L
    }
}
