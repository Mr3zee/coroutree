package com.acme.app;

import kotlinx.coroutree.runtime.Hooks;
import kotlinx.coroutree.runtime.RuntimeProbe;

import java.util.List;

/**
 * Stands for the traced program in tests of the runtime: code of a project package ({@code include=com.acme}) that
 * calls the hooks the way instrumented code would. It has to live outside the agent's own package: the agent's frames
 * are cut off the top of a captured stack and are no candidates for a source site.
 */
public final class App {
    private App() {}

    /** An instrumented blocking method: enter, the wait, exit on the way out whether by return or by exception. */
    public static void blocking(int reason, Runnable waiting) {
        Hooks.blockEnter(reason);
        try {
            waiting.run();
        } finally {
            Hooks.blockExit();
        }
    }

    /** A catch block of project code. */
    public static void catches(Throwable exception) {
        Hooks.exceptionCaught(exception);
    }

    public static void start(Thread thread) {
        Hooks.threadStart(thread);
    }

    public static void interrupt(Thread thread) {
        Hooks.threadInterrupt(thread);
    }

    /** Runs {@code bottom} that many frames of this class further down. */
    public static void deep(int levels, Runnable bottom) {
        if (levels == 0) bottom.run();
        else deep(levels - 1, bottom);
    }

    public static RuntimeException failure(String message) {
        return new IllegalStateException(message);
    }

    /** The stack as the agent captures it here, as {@code Class.method}. */
    public static List<String> capturedStack(int limit) {
        return RuntimeProbe.capture(limit);
    }
}
