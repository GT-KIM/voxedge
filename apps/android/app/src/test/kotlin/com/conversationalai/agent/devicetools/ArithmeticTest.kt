package com.conversationalai.agent.devicetools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline arithmetic evaluator: precedence, percent rewrites, and clean errors on garbage. */
class ArithmeticTest {

    private fun eval(s: String) = Arithmetic.eval(s)

    @Test
    fun basicArithmeticAndPrecedence() {
        assertEquals(7.0, eval("1 + 2 * 3"), 1e-9)
        assertEquals(9.0, eval("(1 + 2) * 3"), 1e-9)
        assertEquals(2.0, eval("8 / 4"), 1e-9)
        assertEquals(-5.0, eval("-2 - 3"), 1e-9)
        assertEquals(8.0, eval("2 ^ 3"), 1e-9)
        assertEquals(512.0, eval("2 ^ 3 ^ 2"), 1e-9)   // right associative: 2^(3^2)
    }

    @Test
    fun decimalsAndThousandsCommas() {
        assertEquals(19.5, eval("12.5 + 7"), 1e-9)
        assertEquals(2000.0, eval("1,000 + 1,000"), 1e-9)
    }

    @Test
    fun percentForms() {
        assertEquals(12.6, eval("15% of 84"), 1e-9)
        assertEquals(12.6, eval("15 percent of 84"), 1e-9)
        assertEquals(0.2, eval("20%"), 1e-9)
        assertEquals(120.0, eval("100 + 20% of 100"), 1e-9)
    }

    @Test
    fun spokenTimesSign() {
        assertEquals(12.0, eval("3 x 4"), 1e-9)
    }

    @Test
    fun errorsAreThrownNotGuessed() {
        assertThrows(Arithmetic.ArithmeticParseException::class.java) { eval("1 +") }
        assertThrows(Arithmetic.ArithmeticParseException::class.java) { eval("(1 + 2") }
        assertThrows(Arithmetic.ArithmeticParseException::class.java) { eval("hello") }
        assertThrows(Arithmetic.ArithmeticParseException::class.java) { eval("1 / 0") }
        assertThrows(Arithmetic.ArithmeticParseException::class.java) { eval("") }
    }

    @Test
    fun realWorldVoiceQueries() {
        assertEquals(17.0, eval("(8 + 9)"), 1e-9)
        assertEquals(25.0, eval("5^2"), 1e-9)
        // tip: 18% of 56.50
        assertTrue(Math.abs(eval("18% of 56.50") - 10.17) < 1e-6)
    }
}
