package kotlinx.coroutree.runtime;

/**
 * Java end of the native monitor probe (src/native/coroutree_monitor.c), which turns JVMTI's monitor-contention events
 * into {@link Hooks#blockEnter}/{@link Hooks#blockExit} calls with reason MONITOR.
 *
 * This class lives in the runtime, not in the agent, because of who calls {@code System.load}: a native library
 * belongs to the class loader of the class that loads it, and only a library of the bootstrap loader can have its
 * native method found by this bootstrap class.
 */
public final class MonitorProbe {
    private MonitorProbe() {}

    /** Implemented in C. Registered by the native agent if it was started with -agentpath, else found in the loaded library. */
    private static native boolean enable(Class<?> hooks);

    /**
     * Connects to a probe that is already in the process because the JVM was started with {@code -agentpath}.
     * Returns whether there was one.
     */
    public static boolean connect() {
        try {
            return enable(Hooks.class);
        } catch (UnsatisfiedLinkError notLoaded) {
            return false;
        }
    }

    /**
     * Loads the probe into the running JVM and connects to it. Throws if the library cannot be loaded or refuses.
     * On JDK 24 and later the JVM answers {@code System.load} from unnamed code with a warning, which is the reason
     * the Gradle plugin goes through {@code -agentpath} instead.
     */
    public static void loadAndConnect(String libraryPath) {
        System.load(libraryPath);
        if (!enable(Hooks.class)) throw new IllegalStateException("the JVM does not provide monitor events");
    }
}
