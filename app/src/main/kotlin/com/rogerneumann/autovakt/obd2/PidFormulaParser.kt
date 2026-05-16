package com.rogerneumann.autovakt.obd2

import java.util.*
import kotlin.math.pow

/**
 * Parses and evaluates Torque Pro style equations.
 * Example: "(A*256+B)/4"
 */
object PidFormulaParser {

    /**
     * Evaluates [equation] against [bytes] then optionally applies piecewise linear
     * calibration via [nonLinearMap] (sorted list of raw→display pairs).
     */
    fun evaluate(
        equation: String,
        bytes: ByteArray,
        nonLinearMap: List<Pair<Float, Float>> = emptyList()
    ): Float {
        var expr = equation.uppercase().replace(" ", "")
        for (i in bytes.indices) {
            val varName = ('A' + i).toString()
            val value = (bytes[i].toInt() and 0xFF).toString()
            expr = expr.replace(Regex("(?<![A-Z])$varName(?![A-Z])"), value)
        }

        val raw = try {
            SimpleExpressionEvaluator.eval(expr).toFloat()
        } catch (e: Exception) {
            return 0f
        }

        if (nonLinearMap.size < 2) return raw
        return piecewiseLinear(raw, nonLinearMap)
    }

    private fun piecewiseLinear(x: Float, map: List<Pair<Float, Float>>): Float {
        val sorted = map.sortedBy { it.first }
        if (x <= sorted.first().first) return sorted.first().second
        if (x >= sorted.last().first) return sorted.last().second
        val hi = sorted.indexOfFirst { it.first >= x }
        val lo = hi - 1
        val (x0, y0) = sorted[lo]
        val (x1, y1) = sorted[hi]
        return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
    }
}

/**
 * A very basic expression evaluator. 
 * Supports +, -, *, /, and parentheses.
 */
private object SimpleExpressionEvaluator {
    fun eval(str: String): Double {
        return object : Any() {
            var pos = -1
            var ch = 0

            fun nextChar() {
                ch = if (++pos < str.length) str[pos].code else -1
            }

            fun eat(charToEat: Int): Boolean {
                while (ch == ' '.code) nextChar()
                if (ch == charToEat) {
                    nextChar()
                    return true
                }
                return false
            }

            fun parse(): Double {
                nextChar()
                val x = parseExpression()
                if (pos < str.length) throw RuntimeException("Unexpected: " + ch.toChar())
                return x
            }

            fun parseExpression(): Double {
                var x = parseTerm()
                while (true) {
                    if (eat('+'.code)) x += parseTerm() // addition
                    else if (eat('-'.code)) x -= parseTerm() // subtraction
                    else return x
                }
            }

            fun parseTerm(): Double {
                var x = parseFactor()
                while (true) {
                    if (eat('*'.code)) x *= parseFactor() // multiplication
                    else if (eat('/'.code)) x /= parseFactor() // division
                    else return x
                }
            }

            fun parseFactor(): Double {
                if (eat('+'.code)) return parseFactor() // unary plus
                if (eat('-'.code)) return -parseFactor() // unary minus

                var x: Double
                val startPos = pos
                if (eat('('.code)) { // parentheses
                    x = parseExpression()
                    eat(')'.code)
                } else if (ch >= '0'.code && ch <= '9'.code || ch == '.'.code) { // numbers
                    while (ch >= '0'.code && ch <= '9'.code || ch == '.'.code) nextChar()
                    x = str.substring(startPos, pos).toDouble()
                } else {
                    throw RuntimeException("Unexpected: " + ch.toChar())
                }

                return x
            }
        }.parse()
    }
}
