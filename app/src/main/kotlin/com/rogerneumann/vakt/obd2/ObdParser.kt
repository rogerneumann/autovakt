package com.rogerneumann.vakt.obd2

import kotlin.math.roundToInt

/**
 * Handles the raw conversion of ELM327 HEX responses into engineering units.
 * Decoupled from the transport layer for 100% unit testability on the JVM.
 */
object ObdParser {

    /**
     * Parses the State of Charge (SOC) from a GM-specific response.
     * PID: 22 02BC
     * Response: 62 02 BC XX YY ...
     * Formula: (XX * 256 + YY) / 512.0
     */
    fun parseGmSoc(rawResponse: String): Float? {
        val bytes = extractPayload(rawResponse, "6202BC") ?: return null
        if (bytes.size < 2) return null
        
        val rawSoc = ((bytes[0].toInt() and 0xFF) * 256 + (bytes[1].toInt() and 0xFF)) / 512.0f
        
        // Non-linear mapping to displayed SOC (approximate)
        // displayed = 1.086 * raw - 5.857
        val displayedSoc = (1.086f * rawSoc) - 5.857f
        return displayedSoc.coerceIn(0f, 100f)
    }

    /**
     * Parses HV Battery Pack Voltage.
     * PID: 22 02BD
     * Formula: (XX * 256 + YY) * 0.0625
     */
    fun parseGmVoltage(rawResponse: String): Float? {
        val bytes = extractPayload(rawResponse, "6202BD") ?: return null
        if (bytes.size < 2) return null
        
        return ((bytes[0].toInt() and 0xFF) * 256 + (bytes[1].toInt() and 0xFF)) * 0.0625f
    }

    /**
     * Parses HV Battery Pack Current.
     * PID: 22 02BE
     * Formula: (XX * 256 + YY) * 0.0625 (Signed 16-bit)
     */
    fun parseGmCurrent(rawResponse: String): Float? {
        val bytes = extractPayload(rawResponse, "6202BE") ?: return null
        if (bytes.size < 2) return null
        
        val raw = (bytes[0].toInt() shl 8) or (bytes[1].toInt() and 0xFF)
        val signedRaw = raw.toShort() // Convert to signed 16-bit
        return signedRaw * 0.0625f
    }

    /**
     * Parses standard 2-byte PIDs (like RPM, Speed).
     * RPM: 01 0C -> ((A*256)+B)/4
     */
    fun parseRpm(rawResponse: String): Int? {
        val bytes = extractPayload(rawResponse, "410C") ?: return null
        if (bytes.size < 2) return null
        
        return ((bytes[0].toInt() and 0xFF) * 256 + (bytes[1].toInt() and 0xFF)) / 4
    }

    /**
     * Parses Vehicle Speed (Standard OBD2).
     * Speed: 01 0D -> A (km/h)
     */
    fun parseSpeedKmh(rawResponse: String): Float? {
        val bytes = extractPayload(rawResponse, "410D") ?: return null
        if (bytes.isEmpty()) return null
        
        return (bytes[0].toInt() and 0xFF).toFloat()
    }

    /**
     * Unit Conversions
     */
    fun kmhToMph(kmh: Float): Float = kmh * 0.621371f
    
    fun celsiusToFahrenheit(c: Float): Float = (c * 9/5) + 32

    fun calculatePowerKw(voltage: Float, current: Float): Float {
        return (voltage * current) / 1000f
    }

    /**
     * Efficiency Calculation
     * Returns mi/kWh
     */
    fun calculateEfficiency(mph: Float, kw: Float): Float {
        if (kw <= 0.5f) return 0f // Avoid division by zero or jitter at standstill
        return mph / kw
    }

    /**
     * Helper to extract the payload bytes after the expected header/PID response.
     */
    private fun extractPayload(rawResponse: String, expectedPrefix: String): ByteArray? {
        val clean = rawResponse.replace(" ", "").trim().uppercase()
        if (!clean.startsWith(expectedPrefix)) return null
        
        val payloadHex = clean.substring(expectedPrefix.length)
        if (payloadHex.length % 2 != 0) return null
        
        return try {
            val bytes = ByteArray(payloadHex.length / 2)
            for (i in bytes.indices) {
                bytes[i] = payloadHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            bytes
        } catch (e: Exception) {
            null
        }
    }
}
