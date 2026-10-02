package samples;

import kotlin.Unit;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.Job;

/** The Java half of {@code InlineAcrossFiles}: to Java an inline function is a method like any other. */
public final class JavaInlineCaller {
    private JavaInlineCaller() {}

    public static Job launchFromJava(CoroutineScope scope) {
        return InlineHelpersKt.launchLabelled(scope, "from java", (inner, continuation) -> Unit.INSTANCE);
    }
}
