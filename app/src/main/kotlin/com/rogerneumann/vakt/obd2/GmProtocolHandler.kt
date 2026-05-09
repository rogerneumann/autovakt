package com.rogerneumann.vakt.obd2

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles GM-specific protocol sequences, including VIN discovery,
 * ECU header management, and multi-frame message reassembly.
 */
@Singleton
class GmProtocolHandler @Inject constructor(private val queue: ElmCommandQueue) {

    private var currentHeader: String? = null

    /**
     * Attempts to retrieve the 17-character VIN from the vehicle.
     * Uses Mode 09 PID 02.
     */
    suspend fun discoverVin(): String? {
        ensureHeader("7E0") // ECM header for VIN
        val rawResponse = queue.execute("09 02", timeoutMs = 5000)
        val hexString = reassembleMultiline(rawResponse)
        return decodeVin(hexString)
    }

    /**
     * Requests the State of Charge (SOC) from the BMS.
     * Header: 7E4, PID: 22 02BC
     */
    suspend fun requestSoc(): Float? {
        ensureHeader("7E4")
        val response = queue.execute("22 02BC")
        return ObdParser.parseGmSoc(response)
    }

    /**
     * Requests HV Battery Voltage.
     * Header: 7E4, PID: 22 02BD
     */
    suspend fun requestVoltage(): Float? {
        ensureHeader("7E4")
        val response = queue.execute("22 02BD")
        return ObdParser.parseGmVoltage(response)
    }

    /**
     * Requests HV Battery Current.
     * Header: 7E4, PID: 22 02BE
     */
    suspend fun requestCurrent(): Float? {
        ensureHeader("7E4")
        val response = queue.execute("22 02BE")
        return ObdParser.parseGmCurrent(response)
    }

    /**
     * Sets the ELM327 ECU header if it's different from the current one.
     */
    private suspend fun ensureHeader(header: String) {
        if (currentHeader != header) {
            queue.execute("ATSH $header")
            currentHeader = header
        }
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
            if (clean.isEmpty() || clean == ">") continue
            
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
        if (hex.length < 34) return null
        
        return try {
            val vinHex = if (hex.startsWith("4902")) {
                hex.substring(6)
            } else {
                hex
            }
            
            val result = StringBuilder()
            for (i in 0 until (vinHex.length / 2)) {
                val byteHex = vinHex.substring(i * 2, i * 2 + 2)
                val charCode = byteHex.toInt(16)
                if (charCode in 32..126) {
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
