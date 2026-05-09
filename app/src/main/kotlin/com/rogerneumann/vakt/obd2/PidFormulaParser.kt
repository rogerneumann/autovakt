package com.rogerneumann.vakt.obd2

import java.util.*
import kotlin.math.pow

/**
 * Parses and evaluates Torque Pro style equations.
 * Example: "(A*256+B)/4"
 */
object PidFormulaParser {

    /**
     * Evaluates the given [equation] using the provided [bytes].
     * @param bytes The data bytes returned by the ELM327 (A = bytes[0], B = bytes[1], etc.)
     */
    fun evaluate(equation: String, bytes: ByteArray): Float {
        var expr = equation.uppercase().replace(" ", "")
        
        // Substitute variables A, B, C... with their numeric values
        for (i in bytes.indices) {
            val char = ('A' + i).toString()
            val value = (bytes[i].toInt() and 0xFF).toString()
            // Use regex to replace only whole word variables (not inside other words, though here it's simple)
            expr = expr.replace(Regex("(?<![A-Z])$char(?![A-Z])"), value)
        }

        return try {
            SimpleExpressionEvaluator.eval(expr).toFloat()
        } catch (e: Exception) {
            0f
        }
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
