package com.rogerneumann.autovakt.data

import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.rogerneumann.autovakt.auto.render.GaugeTheme
import com.rogerneumann.autovakt.util.SunriseSunsetCalculator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LightingManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: SharedPreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // null = sensor hardware not present or no reading received yet
    private var latestLux: Float? = null

    private val _theme = MutableStateFlow(GaugeTheme.DARK)
    val theme: StateFlow<GaugeTheme> = _theme.asStateFlow()

    // AA: no ambient sensor (phone sensor ≠ car display lighting)
    private val _themeForAA = MutableStateFlow(GaugeTheme.DARK)
    val themeForAA: StateFlow<GaugeTheme> = _themeForAA.asStateFlow()

    init {
        update()
        scope.launch { timeUpdateLoop() }
        scope.launch { sensorUpdateLoop() }
    }

    fun refresh() = update()

    fun updateLocation(lat: Double, lon: Double) {
        prefs.edit()
            .putFloat(KEY_CACHED_LAT, lat.toFloat())
            .putFloat(KEY_CACHED_LON, lon.toFloat())
            .apply()
        update()
    }

    fun saveSettings(
        mode: LightingMode,
        dawnHour: Float,
        duskHour: Float,
        useLocation: Boolean,
        luxThreshold: Float
    ) {
        prefs.edit()
            .putString(KEY_MODE, mode.name)
            .putFloat(KEY_DAWN, dawnHour)
            .putFloat(KEY_DUSK, duskHour)
            .putBoolean(KEY_USE_LOCATION, useLocation)
            .putFloat(KEY_LUX, luxThreshold)
            .apply()
        update()
    }

    // ── Pref readers (used by SettingsActivity to populate UI) ───────────────

    fun getMode(): LightingMode =
        LightingMode.valueOf(prefs.getString(KEY_MODE, LightingMode.DARK.name) ?: LightingMode.DARK.name)

    fun getDawnHour(): Float = prefs.getFloat(KEY_DAWN, 6.0f)
    fun getDuskHour(): Float = prefs.getFloat(KEY_DUSK, 20.0f)
    fun isUseLocation(): Boolean = prefs.getBoolean(KEY_USE_LOCATION, false)
    fun getLuxThreshold(): Float = prefs.getFloat(KEY_LUX, 5000f)
    fun hasCachedLocation(): Boolean {
        val lat = prefs.getFloat(KEY_CACHED_LAT, Float.NaN)
        return !lat.isNaN()
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun update() {
        val mode = getMode()
        _theme.value      = if (isLight(mode, includeSensor = true))  GaugeTheme.LIGHT else GaugeTheme.DARK
        _themeForAA.value = if (isLight(mode, includeSensor = false)) GaugeTheme.LIGHT else GaugeTheme.DARK
    }

    private fun isLight(mode: LightingMode, includeSensor: Boolean): Boolean = when (mode) {
        LightingMode.DARK        -> false
        LightingMode.LIGHT       -> true
        LightingMode.AUTO_TIME   -> isLightByTime()
        LightingMode.AUTO_SENSOR -> if (includeSensor) {
            latestLux?.let { it > getLuxThreshold() } ?: isLightByTime()
        } else isLightByTime()
        LightingMode.AUTO_BEST   -> bestEffortTheme(includeSensor)
    }

    // ── Time logic ────────────────────────────────────────────────────────────

    private fun currentHourDecimal(): Float {
        val cal = Calendar.getInstance()
        return cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60f
    }

    // Returns the effective dawn/dusk hours: GPS sunrise/sunset if a location is stored
    // and isUseLocation() is true, slider hours otherwise.
    private fun computeDawnDusk(): Pair<Float, Float> {
        val sliderPair = Pair(getDawnHour(), getDuskHour())
        if (!isUseLocation()) return sliderPair

        val lat = prefs.getFloat(KEY_CACHED_LAT, Float.NaN)
        val lon = prefs.getFloat(KEY_CACHED_LON, Float.NaN)
        if (lat.isNaN() || lon.isNaN()) return sliderPair  // nothing stored yet

        val utcOffset = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 3_600_000.0
        val result = SunriseSunsetCalculator.getSunriseSunset(lat.toDouble(), lon.toDouble(), utcOffset)
            ?: return sliderPair

        return Pair(result.first.toFloat(), result.second.toFloat())
    }

    private fun isLightByTime(): Boolean {
        val hour = currentHourDecimal()
        val (dawn, dusk) = computeDawnDusk()
        val timeResult = hour in dawn..dusk

        // Plausibility check: GPS says it's night, but local clock says midday (10:00–16:00).
        // This means the cached coordinates are stale or wrong — fall back to slider hours.
        if (!timeResult && hour in 10f..16f) {
            return hour in getDawnHour()..getDuskHour()
        }
        return timeResult
    }

    // ── Best-effort mux: time + lux ───────────────────────────────────────────

    // Combines time-based and sensor-based signals for AUTO_BEST mode.
    // When both signals are available they cross-validate; strong lux overrides time
    // to handle tunnels, garages, and direct sunlight. AA path (includeSensor=false)
    // gets plausibility-checked time only.
    private fun bestEffortTheme(includeSensor: Boolean): Boolean {
        val timeLight = isLightByTime()
        val lux = if (includeSensor) latestLux else null

        if (lux == null) return timeLight  // sensor absent or no reading yet

        val threshold = getLuxThreshold()

        // Strong signal: override time entirely
        if (lux > threshold * 1.5f) return true   // very bright — direct sun, outdoors
        if (lux < threshold * 0.1f) return false  // very dark — tunnel, garage, night

        // Moderate lux: time is primary, but lux must agree enough to keep the decision.
        // Time says light → stay light unless lux falls below 40 % of threshold.
        // Time says dark  → flip to light only if lux climbs above 60 % of threshold.
        return if (timeLight) lux > threshold * 0.4f else lux > threshold * 0.6f
    }

    // ── Background loops ──────────────────────────────────────────────────────

    private suspend fun timeUpdateLoop() {
        while (true) {
            delay(60_000)
            val m = getMode()
            if (m == LightingMode.AUTO_TIME || m == LightingMode.AUTO_BEST) update()
        }
    }

    private suspend fun sensorUpdateLoop() {
        lightSensorFlow().collect { lux ->
            latestLux = lux
            val m = getMode()
            if (m == LightingMode.AUTO_SENSOR || m == LightingMode.AUTO_BEST) update()
        }
    }

    private fun lightSensorFlow(): Flow<Float> = callbackFlow {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (sensor == null) { close(); return@callbackFlow }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) { trySend(event.values[0]) }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        awaitClose { sensorManager.unregisterListener(listener) }
    }

    companion object {
        private const val KEY_MODE         = "display_mode"
        private const val KEY_DAWN         = "display_dawn_hour"
        private const val KEY_DUSK         = "display_dusk_hour"
        private const val KEY_USE_LOCATION = "display_use_location"
        private const val KEY_LUX          = "display_lux_threshold"
        private const val KEY_CACHED_LAT   = "display_cached_lat"
        private const val KEY_CACHED_LON   = "display_cached_lon"
    }
}
