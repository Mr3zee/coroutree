package kotlinx.coroutree.gradle

import java.io.File

/**
 * Finds the `package` declaration of a Kotlin or Java source file without parsing the file.
 *
 * Only what may legally precede the declaration is understood: whitespace, comments (nested ones too, Kotlin allows
 * them) and annotations (`@file:JvmName("…")`, `@file:[A B]`, annotations of `package-info.java`). The scan stops at
 * the first token that is anything else, so the cost does not depend on the size of the file.
 */
internal object PackageScanner {
    /** Enough for any license header; a file whose header is longer is reported as being in the root package. */
    private const val MAX_PREFIX = 64 * 1024

    fun packageOf(file: File): String {
        val buffer = CharArray(MAX_PREFIX)
        val length = file.bufferedReader().use { reader ->
            var total = 0
            while (total < buffer.size) {
                val read = reader.read(buffer, total, buffer.size - total)
                if (read < 0) break
                total += read
            }
            total
        }
        return packageOf(String(buffer, 0, length), kotlin = !file.name.endsWith(".java"))
    }

    // [kotlin] decides whether block comments nest: in Kotlin they do, in Java a second opening inside a comment is
    // just text. (Which is why this is not a KDoc: spelling the opening out here would open one.)
    fun packageOf(source: String, kotlin: Boolean = true): String = Scan(source, nestedComments = kotlin).packageName()

    private class Scan(private val text: String, private val nestedComments: Boolean) {
        private var pos = 0

        fun packageName(): String {
            if (text.startsWith("﻿")) pos = 1
            if (text.startsWith("#!", pos)) skipLine()
            while (true) {
                skipBlank()
                if (pos < text.length && text[pos] == '@') skipAnnotation() else break
            }
            if (!text.startsWith("package", pos)) return ""
            val afterKeyword = pos + "package".length
            if (afterKeyword < text.length && !text[afterKeyword].isWhitespace() && text[afterKeyword] != '`') return ""
            pos = afterKeyword
            skipBlank() // Java lets the name start on the next line
            return qualifiedName()
        }

        private fun qualifiedName(): String {
            val name = StringBuilder()
            while (true) {
                skipInlineBlank()
                val segment = identifier()
                if (segment.isEmpty()) break
                name.append(segment)
                skipInlineBlank()
                if (pos < text.length && text[pos] == '.') {
                    name.append('.')
                    pos++
                } else {
                    break
                }
            }
            return name.toString().trimEnd('.')
        }

        private fun identifier(): String {
            if (pos < text.length && text[pos] == '`') {
                val end = text.indexOf('`', pos + 1)
                if (end < 0) return ""
                return text.substring(pos + 1, end).also { pos = end + 1 }
            }
            val start = pos
            while (pos < text.length && Character.isJavaIdentifierPart(text[pos])) pos++
            return text.substring(start, pos)
        }

        /** Whitespace and comments, across lines. */
        private fun skipBlank() {
            while (pos < text.length) {
                when {
                    text[pos].isWhitespace() -> pos++
                    text.startsWith("//", pos) -> skipLine()
                    text.startsWith("/*", pos) -> skipBlockComment()
                    else -> return
                }
            }
        }

        /** Inside the declaration a line break ends a Kotlin package name, so only same-line blanks are skipped. */
        private fun skipInlineBlank() {
            while (pos < text.length) {
                when {
                    text[pos] == ' ' || text[pos] == '\t' -> pos++
                    text.startsWith("/*", pos) -> skipBlockComment()
                    else -> return
                }
            }
        }

        private fun skipLine() {
            val end = text.indexOf('\n', pos)
            pos = if (end < 0) text.length else end + 1
        }

        private fun skipBlockComment() {
            var depth = 0
            while (pos < text.length) {
                when {
                    text.startsWith("/*", pos) && (depth == 0 || nestedComments) -> {
                        depth++
                        pos += 2
                    }
                    text.startsWith("*/", pos) -> {
                        depth--
                        pos += 2
                        if (depth == 0) return
                    }
                    else -> pos++
                }
            }
        }

        private fun skipAnnotation() {
            pos++ // @
            while (pos < text.length) {
                val c = text[pos]
                if (Character.isJavaIdentifierPart(c) || c == '.') {
                    pos++
                } else if (c == ':') {
                    pos++
                    skipInlineBlank() // `@file: JvmName("…")` is legal
                } else {
                    break
                }
            }
            skipInlineBlank()
            if (pos < text.length && (text[pos] == '(' || text[pos] == '[')) skipBrackets()
        }

        private fun skipBrackets() {
            var depth = 0
            while (pos < text.length) {
                val c = text[pos]
                when {
                    c == '(' || c == '[' -> {
                        depth++
                        pos++
                    }
                    c == ')' || c == ']' -> {
                        depth--
                        pos++
                        if (depth == 0) return
                    }
                    text.startsWith("\"\"\"", pos) -> {
                        val end = text.indexOf("\"\"\"", pos + 3)
                        pos = if (end < 0) text.length else end + 3
                    }
                    c == '"' || c == '\'' -> skipQuoted(c)
                    text.startsWith("//", pos) -> skipLine()
                    text.startsWith("/*", pos) -> skipBlockComment()
                    else -> pos++
                }
            }
        }

        private fun skipQuoted(quote: Char) {
            pos++
            while (pos < text.length && text[pos] != quote && text[pos] != '\n') {
                if (text[pos] == '\\') pos++
                pos++
            }
            pos++
        }
    }
}
