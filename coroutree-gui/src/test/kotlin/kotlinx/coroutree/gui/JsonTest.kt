package kotlinx.coroutree.gui

import kotlinx.coroutree.gui.source.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Json reads session files that another process can be writing at that moment, so what matters as much as what it
 * accepts is that everything else ends in a ParseException and in nothing else: no index out of bounds, no number
 * format exception.
 */
class JsonTest {
    private fun rejected(text: String) {
        assertFailsWith<Json.ParseException>("accepted, or failed with something else: $text") { Json.parse(text) }
    }

    @Test
    fun readsEveryKindOfValueAndEveryEscape() {
        assertEquals(
            mapOf("s" to "plain", "empty" to "", "long" to 9_007_199_254_740_993L, "neg" to -1L, "zero" to 0L, "real" to -2.5, "exp" to 1500.0, "yes" to true, "no" to false, "nothing" to null),
            Json.parse("""{"s":"plain","empty":"","long":9007199254740993,"neg":-1,"zero":0,"real":-2.5,"exp":1.5e3,"yes":true,"no":false,"nothing":null}"""),
        )
        assertEquals(mapOf("k" to "\" \\ / \b \u000C \n \r \t"), Json.parse("""{"k":"\" \\ \/ \b \f \n \r \t"}"""))
        assertEquals(mapOf("k" to "é€ \uD83D\uDE00 A"), Json.parse("""{"k":"\u00e9\u20AC \ud83d\ude00 \u0041"}"""), "escaped, lower and upper case hex, a surrogate pair")
        assertEquals(mapOf("ключ" to "значение \uD83D\uDE00 C:\\dir"), Json.parse("""{"ключ":"значение 😀 C:\\dir"}"""), "anything unescaped is itself")
        assertEquals(mapOf("a" to 1L, "b" to "x"), Json.parse("\n\t{ \"a\"\n:\r\n1 ,\t\"b\" : \"x\" }\n  "), "whitespace wherever JSON allows it")
        assertEquals(listOf("z", "a", "m"), Json.parse("""{"z":1,"a":2,"m":3}""").keys.toList(), "keys in the order of the file")
        assertEquals(mapOf("k" to "{[,:]}"), Json.parse("""{"k":"{[,:]}"}"""), "punctuation inside a string is text")
    }

    @Test
    fun aNumberTooBigForALongIsStillANumber() {
        val value = Json.parse("""{"n":92233720368547758070}""")["n"]
        assertEquals(9.223372036854776E19, value)
    }

    @Test
    fun readsNestedObjectsAndArrays() {
        assertEquals(
            mapOf("name" to "IntelliJ IDEA", "launch" to listOf(mapOf("os" to "macOS", "launcherPath" to "../MacOS/idea"), mapOf("os" to "Linux")), "empty" to emptyMap<String, Any?>(), "none" to emptyList<Any?>(), "deep" to listOf(listOf(1L))),
            Json.parse("""{"name":"IntelliJ IDEA","launch":[{"os":"macOS","launcherPath":"../MacOS/idea"},{"os":"Linux"}],"empty":{},"none":[ ],"deep":[[1]]}"""),
        )
    }

    @Test
    fun rejectsWhatIsNotOneObject() {
        val malformed = listOf(
            "", "   ", "null", "\"a\"", "1", "[]", "[{\"a\":1}]",
            "{", "{\"a\"", "{\"a\":", "{\"a\":1", "{\"a\":1,", "{\"a\":1,}", "{,}", "{\"a\":1,,\"b\":2}", "{\"a\":1 \"b\":2}", "{\"a\" 1}", "{\"a\":}", "{:1}",
            "{a:1}", "{'a':1}", "{\"a\":'x'}", "{1:2}",
            "{\"a\":1}x", "{\"a\":1}{}", "{}{}", "{\"a\":1},",
            "{\"a\":\"x}", "{\"a\":\"x\\\"}", "{\"a\":\"\\q\"}", "{\"a\":\"\\", "{\"a\":\"\\u12\"}", "{\"a\":\"\\u12", "{\"a\":\"\\uZZZZ\"}", "{\"a\":\"\\u 041\"}",
            "{\"a\":tru}", "{\"a\":truefalse}", "{\"a\":nul}", "{\"a\":True}", "{\"a\":undefined}",
            "{\"a\":-}", "{\"a\":1.2.3}", "{\"a\":1e}", "{\"a\":--1}", "{\"a\":+1}", "{\"a\":.}", "{\"a\":1-2}",
            "{\"a\":{}", "{\"a\":{\"b\":1}", "{\"a\":[}", "{\"a\":[1,2}", "{\"a\":[1,]}", "{\"a\":[,]}", "{\"a\":{,}}",
        )
        malformed.forEach(::rejected)
    }

    /** SessionRegistry.sessions: "Skips unreadable or invalid descriptors", whatever was written so far. */
    @Test
    fun everyHalfWrittenSessionFileIsRejectedCleanly() {
        val whole = """{"pid":4242,"port":51234,"token":"a\"b\\c\u00e9","traceFile":"/b/build/coroutree/traces/x/run.ctrace","taskPath":":app:run","startedAt":1789668900000,"paceable":true,"ended":false,"load":1.5e0,"note":null}"""
        assertEquals(10, Json.parse(whole).size)
        for (length in 0 until whole.length) rejected(whole.substring(0, length))
    }

    /** RFC 8259 §7: an escape is `\u` followed by exactly four hexadecimal digits. A sign is not a digit. */
    @Test
    fun aUnicodeEscapeIsFourHexDigitsAndASignIsNotOne() {
        rejected("""{"a":"\u+041"}""")
        rejected("""{"a":"\u-041"}""")
    }
}
