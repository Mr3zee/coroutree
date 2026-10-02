package kotlinx.coroutree.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Where the stand-in for {@code Hooks} that {@link Lab} generates writes down every call it gets. Patched test classes
 * run against that stand-in, so a test sees exactly which hooks its bytecode called, in order, with which arguments.
 */
public final class HookLog {
    private HookLog() {}

    private static final List<Object[]> CALLS = Collections.synchronizedList(new ArrayList<>());

    /** Called by generated code: the hook's name and its arguments, boxed. Also by fixtures, to mark their own moments. */
    public static void record(String hook, Object[] arguments) {
        Object[] call = new Object[arguments.length + 1];
        call[0] = hook;
        System.arraycopy(arguments, 0, call, 1, arguments.length);
        CALLS.add(call);
    }

    public static void mark(String what) {
        record(what, new Object[0]);
    }

    /** The calls since the last time, each as name and arguments, and forgets them. */
    static List<Object[]> take() {
        synchronized (CALLS) {
            List<Object[]> calls = new ArrayList<>(CALLS);
            CALLS.clear();
            return calls;
        }
    }

    /** The same as text: {@code blockEnter(5)}; an argument is its toString, or for a throwable its class and message. */
    static List<String> taken() {
        List<String> result = new ArrayList<>();
        for (Object[] call : take()) {
            StringBuilder text = new StringBuilder((String) call[0]).append('(');
            for (int i = 1; i < call.length; i++) {
                if (i > 1) text.append(", ");
                Object argument = call[i];
                text.append(argument instanceof Throwable t ? t.getClass().getSimpleName() + ":" + t.getMessage() : String.valueOf(argument));
            }
            result.add(text.append(')').toString());
        }
        return result;
    }
}
