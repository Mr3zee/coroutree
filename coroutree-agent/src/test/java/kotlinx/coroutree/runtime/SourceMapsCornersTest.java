package kotlinx.coroutree.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Source maps beyond what kotlinc writes today (those are SourceMapsTest's): the corners of JSR-45 the parser
 * takes on, and what happens when the class file and the map do not tell the same story. Every class has a name of
 * its own: maps are kept per class name for the life of the JVM.
 */
class SourceMapsCornersTest {
    private static final String HEAD = "SMAP\nMain.kt\nKotlin\n";

    private static String files(String className) {
        return "*F\n+ 1 Main.kt\ncom/acme/" + className + "\n+ 2 Util.kt\ncom/acme/util/UtilKt\n";
    }

    private static List<String> frames(String className, int line) {
        return RuntimeProbe.logicalFrames("com.acme." + className, "run", "Main.kt", line);
    }

    @Test
    void aRangeMapsEveryOutputLineOfItsStepToTheSameInputLine() {
        // InputStartLine#File,RepeatCount:OutputStartLine,OutputLineIncrement: three input lines, two output lines each.
        SourceMaps.register("com.acme.Stepped", HEAD + "*S Kotlin\n" + files("Stepped") + "*L\n1#1,50:1\n10#2,3:100,2\n"
            + "*S KotlinDebug\n" + files("Stepped") + "*L\n7#1:100,6\n*E\n", null);
        String site = "com.acme.Stepped.run(Main.kt:7)";
        assertEquals(List.of("inline com.acme.util.UtilKt.(Util.kt:10)", site), frames("Stepped", 100));
        assertEquals(List.of("inline com.acme.util.UtilKt.(Util.kt:10)", site), frames("Stepped", 101));
        assertEquals(List.of("inline com.acme.util.UtilKt.(Util.kt:11)", site), frames("Stepped", 102));
        assertEquals(List.of("inline com.acme.util.UtilKt.(Util.kt:12)", site), frames("Stepped", 105));
        assertEquals(List.of("com.acme.Stepped.run(Main.kt:106)"), frames("Stepped", 106), "one past the range");
        assertEquals(List.of("com.acme.Stepped.run(Main.kt:99)"), frames("Stepped", 99), "one before it");
    }

    @Test
    void theMapIsReadWhateverItsLineEndsItsOrderOfStrataAndItsOtherSections() {
        String body = "*S Kotlin\n" + files("Shuffled") + "*L\n1#1,35:1\n23#2,3:36\n";
        String callSite = "*S KotlinDebug\n" + files("Shuffled") + "*L\n14#1:36,3\n";
        // A vendor section, strata of other languages, the call sites before the bodies, no *E at the end, CRLF.
        String map = HEAD + "*V\nsomething of a vendor's\n*S Other\n*F\n1 other.x\n*L\n1#1,500:1\n" + callSite + body;
        SourceMaps.register("com.acme.Shuffled", map.replace("\n", "\r\n"), null);
        assertEquals(List.of("inline com.acme.util.UtilKt.(Util.kt:25)", "com.acme.Shuffled.run(Main.kt:14)"), frames("Shuffled", 38));
        assertEquals(List.of("com.acme.Shuffled.run(Main.kt:20)"), frames("Shuffled", 20));
    }

    @Test
    void aClassDefinedAgainHasTheMapOfWhicheverWasLoadedLast() {
        String name = "com.acme.Reloaded";
        SourceMaps.register(name, HEAD + "*S Kotlin\n" + files("Reloaded") + "*L\n23#2,3:36\n*S KotlinDebug\n" + files("Reloaded") + "*L\n14#1:36,3\n*E\n", null);
        assertEquals(2, frames("Reloaded", 36).size());
        SourceMaps.register(name, HEAD + "*S Kotlin\n" + files("Reloaded") + "*L\n40#2,3:60\n*S KotlinDebug\n" + files("Reloaded") + "*L\n15#1:60,3\n*E\n", null);
        assertEquals(List.of("com.acme.Reloaded.run(Main.kt:36)"), frames("Reloaded", 36));
        assertEquals(List.of("inline com.acme.util.UtilKt.(Util.kt:41)", "com.acme.Reloaded.run(Main.kt:15)"), frames("Reloaded", 61));
    }

    @Test
    void aClassDefinedAgainWithoutInlinedCodeHasNoMapAnyMore() {
        // "A class that two loaders define differently has the map of whichever was loaded last": the one loaded last
        // has only its own lines, so line 36 of it is its line 36, not what the first definition had inlined there.
        String name = "com.acme.Replaced";
        SourceMaps.register(name, HEAD + "*S Kotlin\n" + files("Replaced") + "*L\n1#1,35:1\n23#2,3:36\n*S KotlinDebug\n" + files("Replaced") + "*L\n14#1:36,3\n*E\n", null);
        assertEquals(2, frames("Replaced", 36).size());
        SourceMaps.register(name, HEAD + "*S Kotlin\n*F\n+ 1 Main.kt\ncom/acme/Replaced\n*L\n1#1,80:1\n*E\n", null);
        assertEquals(List.of("com.acme.Replaced.run(Main.kt:36)"), frames("Replaced", 36));
    }

    @Test
    void inlineCallsTheClassFileNamesButTheMapDoesNotKnowEndTheChainNotTheStack() {
        // Line 53 is in withLock, inlined through retry and outer; but the line retry is said to be called from is no line of any copy.
        SourceMaps.register("com.acme.Chained", HEAD + "*S Kotlin\n" + files("Chained") + "*L\n1#1,35:1\n23#2,3:36\n117#2,10:52\n"
            + "*S KotlinDebug\n" + files("Chained") + "*L\n14#1:36,3\n18#1:52,10\n*E\n",
            Map.of(53, new SourceMaps.Inlined(new String[] {"withLock", "retry", "outer"}, new int[] {37, 999, 18}),
                // A line the map does not have as inlined code: whatever the variable tables said about it is nothing.
                20, new SourceMaps.Inlined(new String[] {"ghost"}, new int[] {19})));
        assertEquals(
            List.of("inline com.acme.util.UtilKt.withLock(Util.kt:118)", "inline com.acme.util.UtilKt.retry(Util.kt:24)", "com.acme.Chained.run(Main.kt:18)"),
            frames("Chained", 53));
        assertEquals(List.of("com.acme.Chained.run(Main.kt:20)"), frames("Chained", 20));
    }

    @Test
    void linesThatAreNoLinesAreLeftAlone() {
        SourceMaps.register("com.acme.Unknown", HEAD + "*S Kotlin\n" + files("Unknown") + "*L\n23#2,3:36\n*S KotlinDebug\n" + files("Unknown") + "*L\n14#1:36,3\n*E\n", null);
        // Native (-2), no line information (-1): unknown, which the format spells 0.
        assertEquals(List.of("com.acme.Unknown.run(Main.kt:0)"), frames("Unknown", -2));
        assertEquals(List.of("com.acme.Unknown.run(Main.kt:0)"), frames("Unknown", -1));
        assertEquals(List.of("com.acme.Unknown.run(Main.kt:0)"), frames("Unknown", 0));
        assertEquals(List.of("com.acme.Unknown.run(Main.kt:2147483647)"), frames("Unknown", Integer.MAX_VALUE));
    }
}
