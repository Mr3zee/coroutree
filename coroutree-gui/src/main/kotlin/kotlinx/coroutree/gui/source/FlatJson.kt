package kotlinx.coroutree.gui.source

/**
 * Parser for the one JSON shape the GUI reads: a single object of strings, numbers, booleans and nulls
 * (the agent's session file). Nested values are rejected; pulling in a JSON library for this is not worth the jar size.
 */
internal object FlatJson {
    class ParseException(message: String) : Exception(message)

    fun parse(text: String): Map<String, Any?> = Parser(text).parseObject()

    private class Parser(private val text: String) {
        private var pos = 0

        fun parseObject(): Map<String, Any?> {
            val result = LinkedHashMap<String, Any?>()
            expect('{')
            if (peek() == '}') {
                pos++
            } else {
                while (true) {
                    val key = parseString()
                    expect(':')
                    result[key] = parseValue()
                    when (val c = next()) {
                        ',' -> continue
                        '}' -> break
                        else -> fail("expected ',' or '}' but found '$c'")
                    }
                }
            }
            skipWhitespace()
            if (pos != text.length) fail("trailing characters")
            return result
        }

        private fun parseValue(): Any? = when (val c = peek()) {
            '"' -> parseString()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            '{', '[' -> fail("nested values are not supported")
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
                                out.append(text.substring(pos, pos + 4).toIntOrNull(16)?.toChar() ?: fail("bad unicode escape"))
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
