package kotlinx.coroutines.testfixture;

import kotlinx.coroutree.runtime.Hooks;
import kotlinx.coroutree.runtime.Wire;

/** Code of kotlinx.coroutines, as far as its class name goes: a dispatcher's worker or an event loop that parks waiting for work. */
public final class EventLoop {
    private EventLoop() {}

    public static void parkForWork(Runnable whileParked) {
        Hooks.blockEnter(Wire.BLOCK_PARK);
        try {
            whileParked.run();
        } finally {
            Hooks.blockExit();
        }
    }
}
