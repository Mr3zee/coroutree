package kotlinx.coroutree.agent.fixtures;

import kotlinx.coroutree.agent.HookLog;

import java.util.function.Consumer;

/**
 * A class to weave hooks into, with what the classes of the hook table have: static and instance methods, wide
 * parameters in front of the ones a hook wants, several returns, loops and handlers of its own (so it has stack map
 * frames that must survive), a constructor, a field and a getter to read arguments from, and a call to hook in front of.
 */
public class Subject {
    public final String name;
    public Object field = "field-value";
    public Consumer<Object> sink = value -> HookLog.record("accepted", new Object[] {value});

    public Subject(String name) {
        if (name == null) throw new IllegalArgumentException("no name");
        this.name = name;
    }

    public Subject() {
        this("default");
    }

    /** Like a blocking JDK method: static, a wide parameter, its own handler inside a loop, two returns, a way out by exception. */
    public static long sleep(long millis, String note) {
        if (millis < 0) throw new IllegalArgumentException("negative");
        long total = 0;
        for (int i = 0; i < 3; i++) {
            try {
                if (i == 1 && millis == 13) throw new IllegalStateException("inner");
                total += millis;
            } catch (IllegalStateException e) {
                total -= 1;
            } finally {
                total += 1000;
            }
        }
        if (note == null) return -total;
        return total;
    }

    public int read(byte[] buffer) {
        if (buffer == null) throw new NullPointerException("buffer");
        if (buffer.length == 0) return -1;
        int count = 0;
        for (byte b : buffer) {
            if (b != 0) count++;
        }
        return count;
    }

    public void run(Runnable task) {
        HookLog.mark("running " + name);
        task.run();
    }

    public String mix(long a, Object b, double c, String d) {
        return a + ":" + b + ":" + c + ":" + d;
    }

    public static String mixStatic(int a, long b, Throwable c) {
        return a + ":" + b + ":" + c.getMessage();
    }

    public Object create(Object seed) {
        if (seed == null) return null;
        return "made of " + seed;
    }

    public boolean childCancelled(Throwable cause) {
        return cause instanceof IllegalStateException;
    }

    public Object context() {
        return "context of " + name;
    }

    public void resumeWith(Object result) {
        if (result instanceof String) {
            sink.accept("s:" + result);
        } else {
            sink.accept(result);
            sink.accept("twice");
        }
        HookLog.mark("resumed");
    }

    public void quiet(Object result) {
        HookLog.mark("quiet");
    }

    public long wide() {
        return 1L << 40;
    }

    public double ratio() {
        return 0.5;
    }

    public float single() {
        return 1.5f;
    }
}
