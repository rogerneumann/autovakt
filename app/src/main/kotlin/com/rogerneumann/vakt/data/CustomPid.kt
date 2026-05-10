package com.rogerneumann.vakt.data

/**
 * Represents a custom OBD2 PID definition.
 * Compatible with Torque Pro CSV format.
 */
data class CustomPid(
    val name: String,
    val shortName: String,
    val modeAndPid: String,
    val equation: String,
    val minValue: Float = 0f,
    val maxValue: Float = 100f,
    val units: String = "",
    val header: String? = null,
    val nonLinearMap: List<Pair<Float, Float>> = emptyList() // (rawValue, displayValue) calibration points
)
