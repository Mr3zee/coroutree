package kotlinx.coroutree.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the runtime keeps per thread: who is in a hook, and which execution unit's blocking calls are being counted. */
@Timeout(60)
class ThreadStateTest {
    static {
        if (Tracer.config == null) Tracer.config = AgentConfig.parse(TracedJvm.GATE_OPTIONS);
    }

    private static final AtomicLong IDS = new AtomicLong(7_000_000);

    private static JobNode unit() {
        return new JobNode(IDS.incrementAndGet(), false, false);
    }

    /** The state of a thread of its own, so that no test sees what another left. */
    private static ThreadState fresh(Thread thread, AtomicReference<ThreadState> state) throws InterruptedException {
        thread.start();
        thread.join();
        return state.get();
    }

    private static ThreadState fresh() throws InterruptedException {
        AtomicReference<ThreadState> state = new AtomicReference<>();
        return fresh(new Thread(() -> state.set(ThreadState.current())), state);
    }

    @Test
    void everyThreadHasItsOwnStateAndOnlyTheAgentsOwnThreadsAreBornInsideAHook() throws Exception {
        ThreadState mine = ThreadState.current();
        assertSame(mine, ThreadState.current());
        assertSame(Thread.currentThread(), mine.thread);

        ThreadState other = fresh();
        assertNotSame(mine, other);
        assertFalse(other.inHook, "a thread of the program is observed");

        AtomicReference<ThreadState> state = new AtomicReference<>();
        ThreadState agent = fresh(new AgentThread("coroutree-test") {
            @Override
            public void run() {
                state.set(ThreadState.current());
            }
        }, state);
        assertTrue(agent.inHook, "the observer is not part of the picture: every hook returns at its guard");

        // The JVM's pseudo-thread that waits for the program to end and runs the shutdown sequence: not the program either.
        ThreadState destroyer = fresh(new Thread(() -> state.set(ThreadState.current()), "DestroyJavaVM"), state);
        assertTrue(destroyer.inHook);
    }

    @Test
    void aUnitThatComesOntoTheThreadGetsBlockingBooksOfItsOwnAndGivesTheOldOnesBackWhenItLeaves() throws Exception {
        ThreadState ts = fresh();
        assertNull(ts.currentUnit());

        // The thread itself is blocked (in runBlocking), reported at depth 1, with nothing running on it.
        ts.blockDepth = 1;
        ts.blockEmittedAt = 1;
        JobNode outer = unit(), inner = unit();

        ts.pushUnit(outer);
        assertSame(outer, ts.currentUnit());
        assertEquals(0, ts.blockDepth);
        assertEquals(0, ts.blockEmittedAt, "a coroutine that sleeps inside runBlocking's event loop is a blocking call of its own");

        // The coroutine sleeps (reported, nested), and inside that an undispatched child starts.
        ts.blockDepth = 2;
        ts.blockEmittedAt = 1;
        ts.blockOtherNodeId = outer.id;
        ts.pushUnit(inner);
        ts.pushUnit(inner); // the resume probe fires for every frame of the call chain
        assertSame(inner, ts.currentUnit());
        assertEquals(0, ts.blockDepth);
        assertEquals(0, ts.blockOtherNodeId);

        ts.popUnit(inner);
        assertSame(outer, ts.currentUnit(), "pushed twice, it was on the thread once");
        assertEquals(2, ts.blockDepth);
        assertEquals(1, ts.blockEmittedAt);
        assertEquals(outer.id, ts.blockOtherNodeId);

        ts.popUnit(inner); // a second report of the same suspension
        ts.popUnit(unit()); // a unit that never was on this thread
        assertSame(outer, ts.currentUnit());
        assertEquals(2, ts.blockDepth);

        ts.popUnit(outer);
        assertNull(ts.currentUnit());
        assertEquals(1, ts.blockDepth);
        assertEquals(1, ts.blockEmittedAt);
        assertEquals(0, ts.blockOtherNodeId);
    }

    @Test
    void aUnitThatLeavesTakesEverythingStartedOnTopOfItAlong() throws Exception {
        ThreadState ts = fresh();
        JobNode bottom = unit(), middle = unit(), top = unit();
        ts.blockDepth = 3;
        ts.pushUnit(bottom);
        ts.blockDepth = 5;
        ts.pushUnit(middle);
        ts.blockDepth = 7;
        ts.pushUnit(top);

        ts.popUnit(middle); // it suspended; what it had started undispatched is off this thread with it
        assertSame(bottom, ts.currentUnit());
        assertEquals(5, ts.blockDepth, "the books are bottom's again, as they were when middle came");
        ts.popUnit(bottom);
        assertNull(ts.currentUnit());
        assertEquals(3, ts.blockDepth);
    }

    @Test
    void unitsThatWereNeverTakenOffDoNotGrowWithoutBound() throws Exception {
        ThreadState ts = fresh();
        JobNode last = null;
        for (int i = 0; i < 100_000; i++) {
            last = unit();
            ts.blockDepth = i;
            ts.pushUnit(last);
        }
        assertSame(last, ts.currentUnit(), "the innermost unit is the one that counts");
        ts.popUnit(last);
        assertEquals(99_999, ts.blockDepth, "and its books are still the right ones");
    }
}
