package kotlinx.coroutines;

import java.util.concurrent.CancellationException;

/**
 * Stand-in for a kotlinx.coroutines whose internals are not what the agent's hook table expects: the class is there,
 * one of the hooked methods is there, the rest are gone.
 */
public class JobSupport {
    public void cancel(CancellationException cause) {
    }
}
