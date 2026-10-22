package kotlinx.coroutree.gui.source

/**
 * Parser for the small JSON files the GUI reads: the agent's session file and the `product-info.json` of a JetBrains
 * installation. A JSON library isn't worth the jar size. Objects are maps in file order, arrays are lists, numbers
 * are `Long` or `Double`.
 */
internal object Json {
    class ParseException(message: String) : Exception(message)

    /** The top-level value must be an object. */
    fun parse(text: String): Map<String, Any?> = Parser(text).parseDocument()

    private class Parser(private val text: String) {
        private var pos = 0

        fun parseDocument(): Map<String, Any?> {
            val result = parseObject()
            skipWhitespace()
            if (pos != text.length) fail("trailing characters")
            return result
        }

        private fun parseObject(): Map<String, Any?> {
            val result = LinkedHashMap<String, Any?>()
            expect('{')
            if (peek() == '}') {
                pos++
                return result
            }
            while (true) {
                val key = parseString()
                expect(':')
                result[key] = parseValue()
                when (val c = next()) {
                    ',' -> continue
                    '}' -> return result
                    else -> fail("expected ',' or '}' but found '$c'")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            val result = ArrayList<Any?>()
            expect('[')
            if (peek() == ']') {
                pos++
                return result
            }
            while (true) {
                result.add(parseValue())
                when (val c = next()) {
                    ',' -> continue
                    ']' -> return result
                    else -> fail("expected ',' or ']' but found '$c'")
                }
            }
        }

        private fun parseValue(): Any? = when (val c = peek()) {
            '"' -> parseString()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            '{' -> parseObject()
            '[' -> parseArray()
            else -> if (c == '-' || c.isDigit()) parseNumber() else fail("unexpected '$c'")
        }

        private fun parseNumber(): Number {
            val start = pos
            while (pos < text.length && (text[pos].isDigit() || text[pos] in "+-.eE")) pos++
            val token = text.substring(start, pos)
            return token.toLongOrNull() ?: token.toDoubleOrNull() ?: fail("bad number '$token'")
        }

        private fun parseString(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                if (pos >= text.length) fail("unterminated string")
                when (val c = text[pos++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (pos >= text.length) fail("unterminated escape")
                        when (val escaped = text[pos++]) {
                            '"', '\\', '/' -> out.append(escaped)
                            'b' -> out.append('\b')
                            'f' -> out.append('')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) fail("bad unicode escape")
                                var code = 0
                                for (i in pos until pos + 4) {
                                    val digit = Character.digit(text[i], 16) // toIntOrNull(16) would take a sign
                                    if (digit < 0) fail("bad unicode escape")
                                    code = code * 16 + digit
                                }
                                out.append(code.toChar())
                                pos += 4
                            }
                            else -> fail("bad escape '\\$escaped'")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun <T> literal(word: String, value: T): T {
            if (!text.startsWith(word, pos)) fail("expected $word")
            pos += word.length
            return value
        }

        private fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        private fun peek(): Char {
            skipWhitespace()
            if (pos >= text.length) fail("unexpected end")
            return text[pos]
        }

        private fun next(): Char = peek().also { pos++ }

        private fun expect(c: Char) {
            val actual = next()
            if (actual != c) fail("expected '$c' but found '$actual'")
        }

        private fun fail(message: String): Nothing = throw ParseException("$message at offset $pos")
    }
}
