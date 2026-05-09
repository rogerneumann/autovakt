package com.rogerneumann.vakt.data

/**
 * Represents a custom OBD2 PID definition.
 * Compatible with Torque Pro CSV format.
 */
data class CustomPid(
    val name: String,
    val shortName: String,
    val modeAndPid: String, // e.g., "2202BC"
    val equation: String,   // e.g., "(A*256+B)/512"
    val minValue: Float = 0f,
    val maxValue: Float = 100f,
    val units: String = "",
    val header: String? = null // e.g., "7E4"
)
