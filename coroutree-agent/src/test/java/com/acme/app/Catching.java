package com.acme.app;

import java.util.List;

/**
 * Project code with every kind of handler a compiler writes: the ones that handle an exception, and the ones that
 * only pass it on (finally, synchronized, try-with-resources, a catch that cleans up and rethrows).
 */
public final class Catching {
    private Catching() {}

    public static String handles(RuntimeException toThrow) {
        try {
            throw toThrow;
        } catch (IllegalStateException e) {
            return "handled " + e.getMessage();
        }
    }

    public static String finallyOnly(Runnable body, List<String> log) {
        try {
            body.run();
            return "done";
        } finally {
            log.add("finally");
        }
    }

    public static String locked(Object lock, Runnable body) {
        synchronized (lock) {
            body.run();
            return "done";
        }
    }

    public static String resource(AutoCloseable resource, Runnable body) throws Exception {
        try (resource) {
            body.run();
            return "done";
        }
    }

    public static String cleansUpAndRethrows(Runnable body, List<String> log) {
        try {
            body.run();
            return "done";
        } catch (RuntimeException e) {
            log.add("cleanup");
            if (log.size() > 100) log.clear();
            throw e;
        }
    }

    public static String wraps(Runnable body) {
        try {
            body.run();
            return "done";
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("wrapped", e);
        }
    }

    public static String rethrowsSometimes(Runnable body, boolean again) {
        try {
            body.run();
            return "done";
        } catch (RuntimeException e) {
            if (again) throw e;
            return "swallowed";
        }
    }

    public static String throwsSomethingElseFromTheSameVariable(Runnable body) {
        try {
            body.run();
            return "done";
        } catch (RuntimeException e) {
            e = new IllegalArgumentException("another");
            throw e;
        }
    }

    public static String multi(Runnable body) {
        try {
            body.run();
            return "done";
        } catch (IllegalStateException | IllegalArgumentException e) {
            return "handled " + e.getClass().getSimpleName();
        }
    }

    public static String nested(RuntimeException toThrow) {
        try {
            try {
                throw toThrow;
            } catch (IllegalStateException e) {
                throw new IllegalArgumentException("from the inner catch", e);
            }
        } catch (IllegalArgumentException e) {
            return "outer handled " + e.getMessage();
        }
    }

    public static int inALoop(List<Runnable> bodies) {
        int failed = 0;
        for (Runnable body : bodies) {
            try {
                body.run();
            } catch (RuntimeException e) {
                failed++;
            }
        }
        return failed;
    }

    public static String ignores(Runnable body) {
        try {
            body.run();
        } catch (RuntimeException ignored) {
        }
        return "went on";
    }
}
