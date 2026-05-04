package com.rogerneumann.vakt.obd2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObdParserTest {

    @Test
    fun `parseSoc correctly calculates percentage from 01 2F hex response`() {
        // 41 2F FF -> 100%
        val response100 = "41 2F FF"
        assertEquals(100f, ObdParser.parseSoc(response100)!!, 0.1f)

        // 41 2F 7F -> ~50%
        val response50 = "41 2F 7F"
        assertEquals(49.8f, ObdParser.parseSoc(response50)!!, 0.1f)

        // 41 2F 00 -> 0%
        val response0 = "41 2F 00"
        assertEquals(0f, ObdParser.parseSoc(response0)!!, 0.1f)
    }

    @Test
    fun `parseSoc returns null for malformed response`() {
        assertNull(ObdParser.parseSoc("NO DATA"))
        assertNull(ObdParser.parseSoc("?"))
        assertNull(ObdParser.parseSoc(""))
    }

    @Test
    fun `calculatePowerKw returns correct value`() {
        // 400V * 100A = 40kW
        assertEquals(40f, ObdParser.calculatePowerKw(400f, 100f), 0.01f)
        
        // 350V * -20A (Regen) = -7kW
        assertEquals(-7f, ObdParser.calculatePowerKw(350f, -20f), 0.01f)
    }

    @Test
    fun `parseRpm correctly calculates from standard 01 0C response`() {
        // 41 0C 1A F8 -> ((26 * 256) + 248) / 4 = 1726 RPM
        val response = "41 0C 1A F8"
        assertEquals(1726, ObdParser.parseRpm(response))
    }
}
