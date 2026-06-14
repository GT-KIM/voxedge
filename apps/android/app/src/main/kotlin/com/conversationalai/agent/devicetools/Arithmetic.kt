package com.conversationalai.agent.devicetools

/**
 * Tiny offline arithmetic evaluator for the `calculate` tool — a 4B-class model is unreliable at
 * multi-digit mental math, so it offloads the sum here and reads back the exact result.
 *
 * Grammar (recursive descent): + - * / ^ , unary minus, parentheses. Percent is handled as a
 * preprocessing rewrite so spoken forms work: "15% of 84" -> (15/100)*84, a bare "20%" -> (20/100).
 * Thousands commas are stripped. Anything unrecognized throws [ArithmeticParseException] so the
 * tool can hand a clean error back to the model instead of a wrong number.
 *
 * Pure Kotlin, ASCII-only — JVM-tested in ArithmeticTest.
 */
object Arithmetic {

    class ArithmeticParseException(message: String) : Exception(message)

    fun eval(raw: String): Double {
        val tokens = tokenize(preprocess(raw))
        val p = Parser(tokens)
        val v = p.parseExpr()
        if (!p.atEnd()) throw ArithmeticParseException("unexpected trailing input")
        if (v.isNaN() || v.isInfinite()) throw ArithmeticParseException("result is not a finite number")
        return v
    }

    /** Spoken/written percent -> explicit division; drop thousands commas and lowercase. */
    private fun preprocess(raw: String): String {
        var s = raw.lowercase().trim()
        s = s.replace(Regex("(\\d),(?=\\d{3}\\b)"), "$1")          // 1,000 -> 1000
        s = s.replace(Regex("\\bof\\b"), " of ")
        // "X% of" / "X percent of" -> "(X/100)*"
        s = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:%|percent)\\s+of\\s+").replace(s) { "(${it.groupValues[1]}/100)*" }
        // remaining "X%" / "X percent" -> "(X/100)"
        s = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:%|percent)").replace(s) { "(${it.groupValues[1]}/100)" }
        s = s.replace("x", "*")    // spoken "3 x 4"; safe since identifiers are not allowed
        return s
    }

    private sealed class Token {
        data class Num(val v: Double) : Token()
        data class Op(val c: Char) : Token()
        object LParen : Token()
        object RParen : Token()
    }

    private fun tokenize(s: String): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || c == '.' -> {
                    val start = i
                    while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                    val numStr = s.substring(start, i)
                    val v = numStr.toDoubleOrNull()
                        ?: throw ArithmeticParseException("bad number '$numStr'")
                    out.add(Token.Num(v))
                }
                c == '(' -> { out.add(Token.LParen); i++ }
                c == ')' -> { out.add(Token.RParen); i++ }
                c in "+-*/^" -> { out.add(Token.Op(c)); i++ }
                else -> throw ArithmeticParseException("unexpected character '$c'")
            }
        }
        if (out.isEmpty()) throw ArithmeticParseException("empty expression")
        return out
    }

    private class Parser(private val tokens: List<Token>) {
        private var pos = 0
        fun atEnd() = pos >= tokens.size
        private fun peek(): Token? = tokens.getOrNull(pos)

        fun parseExpr(): Double {
            var v = parseTerm()
            while (true) {
                val t = peek()
                if (t is Token.Op && (t.c == '+' || t.c == '-')) {
                    pos++
                    val r = parseTerm()
                    v = if (t.c == '+') v + r else v - r
                } else break
            }
            return v
        }

        private fun parseTerm(): Double {
            var v = parsePower()
            while (true) {
                val t = peek()
                if (t is Token.Op && (t.c == '*' || t.c == '/')) {
                    pos++
                    val r = parsePower()
                    if (t.c == '/') {
                        if (r == 0.0) throw ArithmeticParseException("division by zero")
                        v /= r
                    } else {
                        v *= r
                    }
                } else break
            }
            return v
        }

        private fun parsePower(): Double {
            val base = parseUnary()
            val t = peek()
            if (t is Token.Op && t.c == '^') {
                pos++
                val exp = parsePower()   // right associative
                return Math.pow(base, exp)
            }
            return base
        }

        private fun parseUnary(): Double {
            val t = peek()
            if (t is Token.Op && (t.c == '+' || t.c == '-')) {
                pos++
                val v = parseUnary()
                return if (t.c == '-') -v else v
            }
            return parsePrimary()
        }

        private fun parsePrimary(): Double {
            when (val t = peek()) {
                is Token.Num -> { pos++; return t.v }
                is Token.LParen -> {
                    pos++
                    val v = parseExpr()
                    if (peek() !is Token.RParen) throw ArithmeticParseException("missing ')'")
                    pos++
                    return v
                }
                else -> throw ArithmeticParseException("expected a number or '('")
            }
        }
    }
}
