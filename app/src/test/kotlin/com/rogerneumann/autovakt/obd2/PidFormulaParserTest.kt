package com.rogerneumann.autovakt.obd2

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [PidFormulaParser]. Expected values derived by hand-tracing
 * evaluate()/eval()/piecewiseLinear() directly against the equation grammar.
 */
class PidFormulaParserTest {

    @Test
    fun `basic equation evaluates with byte substitution`() {
        val result = PidFormulaParser.evaluate("(A*256+B)/4", byteArrayOf(0x01, 0x00))
        assertEquals(64.0f, result, 0.001f)
    }

    @Test
    fun `SIGNED() converts a high-bit byte to its negative value`() {
        val result = PidFormulaParser.evaluate("SIGNED(A)", byteArrayOf(0xFF.toByte()))
        assertEquals(-1.0f, result, 0.001f)
    }

    @Test
    fun `nonLinearMap interpolates linearly between two points`() {
        val result = PidFormulaParser.evaluate(
            "A", byteArrayOf(50), listOf(0f to 0f, 100f to 200f)
        )
        assertEquals(100.0f, result, 0.001f)
    }

    @Test
    fun `nonLinearMap clamps to first point below range - does not extrapolate`() {
        val result = PidFormulaParser.evaluate(
            "A-10", byteArrayOf(0), listOf(0f to 0f, 100f to 200f)
        )
        assertEquals(0.0f, result, 0.001f)
    }

    @Test
    fun `nonLinearMap clamps to last point above range - does not extrapolate`() {
        val result = PidFormulaParser.evaluate(
            "A+50", byteArrayOf(0x64), listOf(0f to 0f, 100f to 200f)
        )
        assertEquals(200.0f, result, 0.001f)
    }

    @Test
    fun `malformed equation is caught and returns 0f, does not throw`() {
        val result = PidFormulaParser.evaluate("A+", byteArrayOf(0x01))
        assertEquals(0.0f, result, 0.001f)
    }
}
