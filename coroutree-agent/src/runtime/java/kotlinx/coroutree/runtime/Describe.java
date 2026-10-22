package kotlinx.coroutree.runtime;

/** Turns application objects into trace strings. Application code runs here ({@code toString}), so nothing is trusted. */
final class Describe {
    private static final int MAX_VALUE_LENGTH = 200;
    private static final int MAX_MESSAGE_LENGTH = 1000;

    private Describe() {}

    static String value(Object object) {
        String text;
        try {
            text = String.valueOf(object);
            // The default Object.toString says nothing beyond the class, and its hash differs from run to run.
            if (text.equals(object.getClass().getName() + "@" + Integer.toHexString(object.hashCode()))) {
                return simpleName(object.getClass());
            }
        } catch (Throwable e) {
            return simpleName(object.getClass()) + " (toString threw " + e.getClass().getName() + ")";
        }
        // kotlinx.coroutines prints many of its objects as Name@identity; the identity is just as meaningless.
        String identity = "@" + Integer.toHexString(System.identityHashCode(object));
        if (text.endsWith(identity) && text.length() > identity.length()) text = text.substring(0, text.length() - identity.length());
        return truncate(stripTrailingIdentity(text), MAX_VALUE_LENGTH);
    }

    /**
     * {@code Executors$DelegatedExecutorService@3e6fa38a} at the end of a dispatcher's text: the identity of an object
     * inside the element, which differs from run to run as well. Only {@code <identifier>@<hex>} at the very end goes.
     */
    private static String stripTrailingIdentity(String text) {
        int at = text.lastIndexOf('@');
        if (at <= 0 || at == text.length() - 1 || text.length() - at - 1 > 8) return text;
        if (!Character.isJavaIdentifierPart(text.charAt(at - 1))) return text;
        for (int i = at + 1; i < text.length(); i++) if (Character.digit(text.charAt(i), 16) < 0) return text;
        return text.substring(0, at);
    }

    static TraceEvent.ExceptionDef exception(Throwable throwable, boolean withStack, int stackDepth) {
        TraceEvent.ExceptionDef def = new TraceEvent.ExceptionDef();
        def.className = throwable.getClass().getName();
        def.identity = System.identityHashCode(throwable);
        def.cancellation = throwable instanceof java.util.concurrent.CancellationException;
        try {
            String message = throwable.getMessage();
            def.message = message == null ? "" : truncate(message, MAX_MESSAGE_LENGTH);
        } catch (Throwable e) {
            def.message = "";
        }
        if (withStack) {
            try {
                def.stack = StackFrameRef.of(throwable.getStackTrace(), stackDepth);
            } catch (Throwable ignored) {
            }
        }
        return def;
    }

    /** Class name without the package, keeping outer classes: {@code CoroutineScheduler$Worker}. */
    static String simpleName(Class<?> type) {
        String name = type.getName();
        return name.substring(name.lastIndexOf('.') + 1);
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
