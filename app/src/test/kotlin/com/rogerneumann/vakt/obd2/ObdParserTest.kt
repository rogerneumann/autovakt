package com.rogerneumann.vakt.obd2

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ObdParserTest {

    @Test
    fun `parseRpm correctly calculates from standard 01 0C response`() {
        // 41 0C 1A F8 -> ((26 * 256) + 248) / 4 = 1726 RPM
        val response = "41 0C 1A F8"
        assertEquals(1726, ObdParser.parseRpm(response))
    }

    @Test
    fun `parseRpm returns null for wrong prefix`() {
        val response = "41 0D 50"  // Wrong PID (should be 0C)
        assertNull(ObdParser.parseRpm(response))
    }

    @Test
    fun `parseSpeedKmh correctly calculates from standard 01 0D response`() {
        // 41 0D 50 -> 80 km/h (0x50 = 80)
        val response = "41 0D 50"
        assertEquals(80f, ObdParser.parseSpeedKmh(response)!!, 0.1f)
    }

    @Test
    fun `calculatePowerKw returns correct value`() {
        // 400V * 100A = 40kW
        assertEquals(40f, ObdParser.calculatePowerKw(400f, 100f), 0.01f)

        // 350V * -20A (Regen) = -7kW
        assertEquals(-7f, ObdParser.calculatePowerKw(350f, -20f), 0.01f)
    }

    @Test
    fun `kmhToMph conversion is accurate`() {
        // 100 km/h ≈ 62.14 mph
        assertEquals(62.14f, ObdParser.kmhToMph(100f), 0.1f)
    }

    @Test
    fun `celsiusToFahrenheit conversion is accurate`() {
        // 0°C = 32°F
        assertEquals(32f, ObdParser.celsiusToFahrenheit(0f), 0.1f)
        // 100°C = 212°F
        assertEquals(212f, ObdParser.celsiusToFahrenheit(100f), 0.1f)
    }

    @Test
    fun `calculateEfficiency handles zero power gracefully`() {
        // At zero power, efficiency should be 0 to avoid division by zero
        assertEquals(0f, ObdParser.calculateEfficiency(60f, 0f), 0.01f)
        assertEquals(0f, ObdParser.calculateEfficiency(60f, 0.4f), 0.01f)
    }

    @Test
    fun `calculateEfficiency returns mi per kWh`() {
        // 60 mph at 10 kW = 6 mi/kWh
        assertEquals(6f, ObdParser.calculateEfficiency(60f, 10f), 0.1f)
    }
}
