package com.rogerneumann.vakt.obd2

/**
 * Handles the raw conversion of ELM327 HEX responses into engineering units.
 * Decoupled from the transport layer for 100% unit testability on the JVM.
 */
object ObdParser {

    /**
     * Parses the State of Charge (SOC) from a GM-specific response.
     * PID: 01 00 (Standard) or GM Specific 22 24 10 (example)
     * For demonstration, we'll use a standard 01 2F (Fuel Level) or custom GM mapping.
     */
    fun parseSoc(rawResponse: String): Float? {
        val clean = rawResponse.replace(" ", "").trim()
        if (clean.length < 4) return null
        
        return try {
            // Example: 41 2F XX -> (XX / 255) * 100
            val hexValue = clean.substring(clean.length - 2)
            val intValue = hexValue.toInt(16)
            (intValue.toFloat() / 255f) * 100f
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parses Power in kW from Voltage and Current.
     * Often involves two separate PIDs, but some adapters return them combined.
     */
    fun calculatePowerKw(voltage: Float, current: Float): Float {
        return (voltage * current) / 1000f
    }

    /**
     * Parses standard 2-byte PIDs (like RPM, Speed).
     * RPM: 01 0C -> ((A*256)+B)/4
     */
    fun parseRpm(rawResponse: String): Int? {
        val clean = rawResponse.replace(" ", "").trim()
        if (clean.length < 6) return null
        
        return try {
            val a = clean.substring(clean.length - 4, clean.length - 2).toInt(16)
            val b = clean.substring(clean.length - 2).toInt(16)
            ((a * 256) + b) / 4
        } catch (e: Exception) {
            null
        }
    }
}
