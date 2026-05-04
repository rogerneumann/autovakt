package com.rogerneumann.vakt.obd2

/**
 * Handles GM-specific protocol sequences, including VIN discovery 
 * and multi-frame message reassembly.
 */
class GmProtocolHandler(private val queue: ElmCommandQueue) {

    /**
     * Attempts to retrieve the 17-character VIN from the vehicle.
     * Uses Mode 09 PID 02.
     */
    suspend fun discoverVin(): String? {
        // 1. Request VIN
        val rawResponse = queue.execute("09 02", timeoutMs = 5000)
        
        // 2. Reassemble if multiline
        val hexString = reassembleMultiline(rawResponse)
        
        // 3. Extract and Decode ASCII
        return decodeVin(hexString)
    }

    /**
     * ELM327 multiline responses often look like:
     * 0: 49 02 01 XX XX XX
     * 1: XX XX XX XX XX XX
     * ...
     */
    private fun reassembleMultiline(raw: String): String {
        val lines = raw.split("\n", "\r")
        val sb = StringBuilder()
        
        for (line in lines) {
            val clean = line.trim()
            if (clean.isEmpty()) continue
            
            // Strip the "0:", "1:", etc. prefixes
            val data = if (clean.contains(":")) {
                clean.substringAfter(":").trim()
            } else {
                clean
            }
            sb.append(data.replace(" ", ""))
        }
        
        return sb.toString()
    }

    private fun decodeVin(hex: String): String? {
        if (hex.length < 34) return null // 17 chars * 2 hex digits
        
        return try {
            // The VIN usually starts after the Service ID (49) and PID (02)
            // and often a number of items byte. We look for the first ASCII character.
            val vinHex = if (hex.startsWith("4902")) {
                hex.substring(6) // Skip 49 02 and the next byte
            } else {
                hex
            }
            
            val result = StringBuilder()
            for (i in 0 until (vinHex.length / 2)) {
                val byteHex = vinHex.substring(i * 2, i * 2 + 2)
                val charCode = byteHex.toInt(16)
                if (charCode in 32..126) { // Printable ASCII
                    result.append(charCode.toChar())
                }
            }
            
            val vin = result.toString().trim()
            if (vin.length >= 17) vin.substring(0, 17) else null
        } catch (e: Exception) {
            null
        }
    }
}
