package kotlinx.coroutree.runtime;

import com.acme.app.App;
import kotlinx.coroutines.testfixture.EventLoop;
import kotlinx.coroutree.model.Event;
import kotlinx.coroutree.model.EventKind;
import kotlinx.coroutree.model.Frame;
import kotlinx.coroutree.model.NodeInfo;
import kotlinx.coroutree.model.NodeKind;
import kotlinx.coroutree.model.Origin;
import kotlinx.coroutree.runtime.TracedJvm.Recording;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hooks of threads, called the way instrumented JDK code calls them, in a JVM that traces for real: what comes out
 * is read back from the trace file. (The hooks of coroutines need kotlinx.coroutines instrumented, which takes the
 * agent itself: they are the integration tests'.) The JVM has a gate with a pace, so every scenario also checks that
 * each of its events passed the gate: one that did not is an ERROR in the trace.
 */
@Timeout(120)
class HooksTest {
    private static final Runnable NOTHING = () -> {};
    private static final AtomicInteger NAMES = new AtomicInteger();

    @BeforeAll
    static void traceThisJvm() {
        TracedJvm.start();
    }

    private static String name(String what) {
        return what + "-" + NAMES.incrementAndGet();
    }

    // ------------------------------------------------------------------ blocking

    @Test
    void onlyTheOutermostBlockingCallIsReportedAndEveryEnterHasItsExit() throws Exception {
        String name = name("blocker");
        AtomicInteger depth = new AtomicInteger(-1);
        AtomicLong tid = new AtomicLong();
        Recording recording = TracedJvm.record(name, () -> {
            tid.set(Thread.currentThread().threadId());
            // join waits with wait, which parks: one blocking call to whoever looks at the thread.
            App.blocking(Wire.BLOCK_JOIN, () -> App.blocking(Wire.BLOCK_WAIT, () -> App.blocking(Wire.BLOCK_PARK, NOTHING)));
            try {
                App.blocking(Wire.BLOCK_SLEEP, () -> {
                    throw App.failure("interrupted, say");
                });
            } catch (IllegalStateException leftByException) {
                // the exit hook ran on the way out
            }
            depth.set(ThreadState.current().blockDepth);
        });

        NodeInfo thread = recording.node(name);
        assertEquals(List.of("DISCOVERED", "THREAD_BLOCKED(JOIN)", "THREAD_UNBLOCKED", "THREAD_BLOCKED(SLEEP)", "THREAD_UNBLOCKED"), recording.story(thread.getId()));
        assertEquals(0, depth.get());
        assertEquals(List.of(), recording.errors());

        // A thread whose start was not seen is defined when it is first seen, as what it is.
        assertEquals(NodeKind.THREAD, thread.getKind());
        assertEquals(Origin.LIBRARY, thread.getOrigin());
        assertEquals(tid.get(), thread.getThread().getTid());
        assertTrue(thread.getThread().getDaemon());
        assertFalse(thread.getThread().getVirtual());
        assertEquals(0, thread.getParentId());

        List<Event> events = recording.of(thread.getId());
        Event blocked = events.get(1);
        assertEquals(thread.getId(), blocked.getThreadId());
        assertEquals(0, blocked.getOtherNodeId(), "no coroutine was running on it");
        assertEquals("com.acme.app.App.blocking", recording.stack(blocked.getStack()).get(0), "the agent's own frames are not part of a stack");
        assertTrue(blocked.getStack().size() <= TracedJvm.STACK_DEPTH);

        // All five are steps of one sequence, the thread's, in a JVM paced at one step a millisecond: each is at least
        // that far from the one before, by the times in the trace, and the hold itself is nowhere but in held_nanos.
        long held = 0;
        for (int i = 0; i < events.size(); i++) {
            assertFalse(events.get(i).getSameStep(), "one hook call each");
            held += events.get(i).getHeldNanos();
            if (i > 0) {
                long apart = events.get(i).getTimeNanos() - events.get(i - 1).getTimeNanos();
                assertTrue(apart >= TracedJvm.INTERVAL_NANOS, "steps " + (i - 1) + " and " + i + " are " + apart + " ns apart");
            }
        }
        assertTrue(held > 0, "the thread was held, and the trace says for how long");
    }

    @Test
    void aCoroutineRunningOnTheThreadKeepsBlockingBooksOfItsOwn() throws Exception {
        String name = name("event-loop");
        AtomicLong coroutineId = new AtomicLong();
        AtomicInteger depth = new AtomicInteger(-1);
        Recording recording = TracedJvm.record(name, () -> {
            ThreadState ts = ThreadState.current();
            // A thread blocked in runBlocking runs a coroutine that sleeps: the two nest, and both are reported.
            App.blocking(Wire.BLOCK_RUN_BLOCKING, () -> {
                JobNode coroutine = new JobNode(Tracer.newNodeId(), false, false);
                coroutineId.set(coroutine.id);
                ts.pushUnit(coroutine);
                App.blocking(Wire.BLOCK_SLEEP, () -> App.blocking(Wire.BLOCK_PARK, NOTHING));
                ts.popUnit(coroutine);
                App.blocking(Wire.BLOCK_PARK, NOTHING); // the event loop itself, waiting for the next piece of work: still runBlocking
            });
            depth.set(ts.blockDepth);
        });

        NodeInfo thread = recording.node(name);
        assertEquals(List.of("DISCOVERED", "THREAD_BLOCKED(RUN_BLOCKING)", "THREAD_BLOCKED(SLEEP)", "THREAD_UNBLOCKED", "THREAD_UNBLOCKED"), recording.story(thread.getId()));
        List<Long> runningOnIt = new ArrayList<>();
        for (Event event : recording.of(thread.getId())) runningOnIt.add(event.getOtherNodeId());
        assertEquals(List.of(0L, 0L, coroutineId.get(), coroutineId.get(), 0L), runningOnIt,
            "blocked and unblocked name the coroutine that was running on the thread");
        assertEquals(0, depth.get());
        assertEquals(List.of(), recording.errors());
    }

    @Test
    void aBlockingCallThatIsNotReportedStillCountsForTheBalance() throws Exception {
        String name = name("reader");
        List<Integer> depths = new CopyOnWriteArrayList<>();
        Recording recording = TracedJvm.record(name, () -> {
            ThreadState ts = ThreadState.current();
            // Reading a file is not blocking; the exit hook of read() cannot tell and comes all the same.
            Hooks.blockEnterIfStdin(new ByteArrayInputStream(new byte[0]));
            depths.add(ts.blockDepth);
            App.blocking(Wire.BLOCK_PARK, NOTHING); // not the detail of an outer blocking call: there is none
            Hooks.blockExit();
            depths.add(ts.blockDepth);

            // An exit whose enter was before tracing was on (the agent activates in the middle of somebody's sleep).
            Hooks.blockExit();
            depths.add(ts.blockDepth);

            // Standard input blocks for as long as the user likes.
            Hooks.blockEnterIfStdin(new FileInputStream(FileDescriptor.in));
            Hooks.blockExit();
            depths.add(ts.blockDepth);

            // Something that is not a stream at all, null included: an enter is an enter.
            Hooks.blockEnterIfStdin(null);
            depths.add(ts.blockDepth);
            Hooks.blockExit();
            depths.add(ts.blockDepth);
        });

        assertEquals(List.of(1, 0, 0, 0, 1, 0), depths);
        assertEquals(List.of("DISCOVERED", "THREAD_BLOCKED(PARK)", "THREAD_UNBLOCKED", "THREAD_BLOCKED(IO)", "THREAD_UNBLOCKED"),
            recording.story(recording.node(name).getId()));
        assertEquals(List.of(), recording.errors());
    }

    @Test
    void aParkOfTheCoroutineRuntimeIsAnIdleThreadNotABlockedOne() throws Exception {
        String name = name("dispatcher-worker");
        List<Integer> depths = new CopyOnWriteArrayList<>();
        Recording recording = TracedJvm.record(name, () -> {
            ThreadState ts = ThreadState.current();
            EventLoop.parkForWork(() -> depths.add(ts.blockDepth));
            depths.add(ts.blockDepth);
            App.blocking(Wire.BLOCK_PARK, NOTHING); // the program's own park is one
        });
        assertEquals(List.of(1, 0), depths, "counted, though not reported");
        assertEquals(List.of("DISCOVERED", "THREAD_BLOCKED(PARK)", "THREAD_UNBLOCKED"), recording.story(recording.node(name).getId()));
        Event blocked = recording.events.get(1);
        assertEquals("com.acme.app.App.blocking", recording.stack(blocked.getStack()).get(0));
        assertEquals(List.of(), recording.errors());
    }

    @Test
    void theAgentChecksAtAThreadsEndThatItsBlockingCallsWereBalanced() throws Exception {
        assertTrue(Tracer.DEBUG, "the build runs these tests with -Dcoroutree.debug=true, like every JVM of the integration tests");
        String balanced = name("balanced"), unbalanced = name("unbalanced");
        Recording fine = TracedJvm.record(balanced, () -> {
            App.blocking(Wire.BLOCK_SLEEP, NOTHING);
            Hooks.threadExit(Thread.currentThread());
        });
        assertEquals(List.of(), fine.errors());

        Recording broken = TracedJvm.record(unbalanced, () -> {
            Hooks.blockEnter(Wire.BLOCK_SLEEP); // and no exit: what a hook that lost its way would leave behind
            Hooks.threadExit(Thread.currentThread());
        });
        assertEquals(1, broken.errors().size(), broken.errors().toString());
        assertTrue(broken.errors().get(0).contains(unbalanced) && broken.errors().get(0).contains("unbalanced"), broken.errors().get(0));
    }

    // ------------------------------------------------------------------ held at the gate

    private static void awaitHeld(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (thread.getState() != Thread.State.TIMED_WAITING) {
            if (System.nanoTime() > deadline) throw new AssertionError(thread.getName() + " is " + thread.getState() + ", not held");
            Thread.sleep(1);
        }
    }

    /** The kinds of the events of the thread of that name that are in the trace file by now. */
    private static List<String> inTheTraceSoFar(String threadName) {
        long id = 0;
        List<String> kinds = new ArrayList<>();
        for (Frame frame : TraceFiles.read(TracedJvm.TRACE)) {
            Event event = frame.getEvent();
            if (event == null) continue;
            if (event.getNode() != null && event.getNode().getName().equals(threadName)) id = event.getNodeId();
            if (id != 0 && event.getNodeId() == id) kinds.add(event.getKind().name());
        }
        return kinds;
    }

    @Test
    void aThreadHeldAtAPausedGateShowsNothingOfTheHoldAndKeepsItsInterruptFlag() throws Exception {
        String name = name("held");
        AtomicReference<Boolean> flagAfterwards = new AtomicReference<>();
        Recording recording = TracedJvm.record(() -> {
            Pace gate = Pace.GATE;
            gate.command("pause");
            try {
                Thread thread = new Thread(() -> {
                    Thread.currentThread().interrupt(); // the program's own flag, set before it ever reaches an event
                    App.blocking(Wire.BLOCK_SLEEP, NOTHING);
                    flagAfterwards.set(Thread.interrupted());
                }, name);
                thread.setDaemon(true);
                thread.start();

                awaitHeld(thread); // at its first event: being discovered
                assertEquals(List.of(), inTheTraceSoFar(name), "nothing happens from a paused program");

                gate.command("step 1");
                long deadline = System.nanoTime() + 20_000_000_000L;
                while (inTheTraceSoFar(name).isEmpty() && System.nanoTime() < deadline) Thread.sleep(2);
                awaitHeld(thread); // and at its second: about to block
                assertEquals(List.of("DISCOVERED"), inTheTraceSoFar(name), "one step went through, exactly");

                gate.command("resume");
                thread.join(20_000);
                assertFalse(thread.isAlive());
            } finally {
                gate.command("resume");
            }
        });

        // Exactly what the thread did, as if nobody had stopped it: no state, no blocking, no interrupt of the agent's making.
        long thread = recording.node(name).getId();
        assertEquals(List.of("DISCOVERED", "THREAD_BLOCKED(SLEEP)", "THREAD_UNBLOCKED"), recording.story(thread));
        assertEquals(3, recording.events.size());
        assertEquals(Boolean.TRUE, flagAfterwards.get(), "the flag was cleared for the wait and is back");
        // What is recorded is the truth about time and about settings.
        long held = 0;
        for (Event event : recording.of(thread)) held += event.getHeldNanos();
        assertTrue(held > 0);
        List<String> settings = new ArrayList<>();
        for (kotlinx.coroutree.model.PaceDef pace : recording.paces) settings.add((pace.getPaused() ? "paused" : "running") + (pace.getSteps() > 0 ? " +" + pace.getSteps() : ""));
        assertEquals(List.of("paused", "paused +1", "running"), settings.subList(0, 3));
        assertEquals(List.of(), recording.errors());
    }

    // ------------------------------------------------------------------ the guards every hook has

    @Test
    void aHookCalledFromInsideAHookSeesAndCountsNothing() throws Exception {
        // Hooks use the JDK and the JDK is instrumented; and the gate's own sleep comes by blockEnter and blockExit.
        AtomicInteger depth = new AtomicInteger(-1);
        Recording recording = TracedJvm.record(name("in-hook"), () -> {
            ThreadState ts = ThreadState.current();
            ts.inHook = true;
            try {
                App.blocking(Wire.BLOCK_SLEEP, () -> depth.set(ts.blockDepth));
                Hooks.blockEnter(Wire.BLOCK_PARK); // an enter alone, as while the hold sleeps
                depth.compareAndSet(0, ts.blockDepth);
                App.catches(App.failure("inside"));
                App.interrupt(Thread.currentThread());
                App.start(new Thread(NOTHING));
                Hooks.threadUncaught(Thread.currentThread(), App.failure("inside"));
                Hooks.threadExit(Thread.currentThread());
            } finally {
                ts.inHook = false;
            }
        });
        assertEquals(0, depth.get(), "enter and exit alike leave the depth alone");
        assertEquals(List.of(), recording.events);
        assertEquals(List.of(), recording.errors());
    }

    @Test
    void withTracingOffHooksDoNothingAndAnExitWithoutItsEnterIsHarmless() throws Exception {
        String name = name("late");
        List<Integer> depths = new CopyOnWriteArrayList<>();
        Recording recording = TracedJvm.record(name, () -> {
            ThreadState ts = ThreadState.current();
            Tracer.active = false;
            try {
                Hooks.blockEnter(Wire.BLOCK_SLEEP); // asleep while the agent starts
                App.catches(App.failure("unseen"));
                depths.add(ts.blockDepth);
            } finally {
                Tracer.active = true;
            }
            Hooks.blockExit(); // wakes up traced
            depths.add(ts.blockDepth);
            App.blocking(Wire.BLOCK_SLEEP, NOTHING);
            depths.add(ts.blockDepth);
        });
        assertEquals(List.of(0, 0, 0), depths);
        assertEquals(List.of("DISCOVERED", "THREAD_BLOCKED(SLEEP)", "THREAD_UNBLOCKED"), recording.story(recording.node(name).getId()));
        assertEquals(3, recording.events.size());
        assertEquals(List.of(), recording.errors());
    }

    @Test
    void theAgentsOwnThreadsAreNotPartOfThePicture() throws Exception {
        Recording recording = TracedJvm.record(() -> {
            Thread agent = new AgentThread("coroutree-test") {
                @Override
                public void run() {
                    App.blocking(Wire.BLOCK_PARK, NOTHING);
                    App.catches(App.failure("the agent's own"));
                    Hooks.threadUncaught(this, App.failure("the agent's own"));
                    Hooks.threadExit(this);
                }
            };
            Thread starter = new Thread(() -> {
                // Neither its start nor an interrupt of it is anybody's event; and the thread that did both stays unseen.
                App.start(agent);
                App.interrupt(agent);
            }, name("starter-of-agent-thread"));
            starter.start();
            starter.join();
            agent.start();
            agent.join();
        });
        assertEquals(List.of(), recording.events);
        assertEquals(List.of(), recording.errors());
    }

    /** Whatever a hook is handed: it returns, it throws nothing, and it leaves the thread as it found it. */
    @Test
    void aHookNeverThrowsIntoTheProgramWhateverItIsGiven() throws Exception {
        // Not Kotlin's and not kotlinx.coroutines' objects, where the hooks expect them; and null, which no JVM passes.
        Object stray = new Object() {
            @Override
            public String toString() {
                throw new IllegalStateException("toString of an application object");
            }
        };
        Throwable hostile = new RuntimeException() {
            @Override
            public String getMessage() {
                throw new IllegalStateException("getMessage of an application exception");
            }

            @Override
            public String toString() {
                throw new IllegalStateException("toString of an application exception");
            }

            @Override
            public StackTraceElement[] getStackTrace() {
                throw new IllegalStateException("getStackTrace of an application exception");
            }
        };
        Map<String, Runnable> calls = new LinkedHashMap<>();
        calls.put("coroutineCreated", () -> Hooks.coroutineCreated(stray, stray, stray));
        calls.put("coroutineCreated(null)", () -> Hooks.coroutineCreated(null, null, null));
        calls.put("jobCreated", () -> Hooks.jobCreated(stray, stray));
        calls.put("coroutineResumed", () -> Hooks.coroutineResumed(stray, stray));
        calls.put("coroutineResumed(null)", () -> Hooks.coroutineResumed(null, null));
        calls.put("coroutineSuspended", () -> Hooks.coroutineSuspended(stray, stray));
        calls.put("continuationCreated", () -> Hooks.continuationCreated(stray));
        calls.put("continuationCompleted", () -> Hooks.continuationCompleted(stray, stray, stray));
        calls.put("cancelRequested", () -> Hooks.cancelRequested(stray, hostile));
        calls.put("parentCancelled", () -> Hooks.parentCancelled(stray, stray));
        calls.put("cancelling", () -> Hooks.cancelling(stray, hostile));
        calls.put("completing", () -> Hooks.completing(stray, stray));
        calls.put("completed", () -> Hooks.completed(stray, stray));
        calls.put("childCancelled", () -> Hooks.childCancelled(stray, stray, hostile));
        calls.put("childCancelledResult", () -> Hooks.childCancelledResult(false, stray, stray, hostile));
        calls.put("exceptionReachedHandler", () -> Hooks.exceptionReachedHandler(stray, hostile));
        calls.put("exceptionReachedHandler(null)", () -> Hooks.exceptionReachedHandler(null, null));
        calls.put("exceptionCaught", () -> Hooks.exceptionCaught(hostile));
        calls.put("exceptionCaught(null)", () -> Hooks.exceptionCaught(null));
        calls.put("threadStart(null)", () -> Hooks.threadStart(null));
        calls.put("threadInterrupt(null)", () -> Hooks.threadInterrupt(null));
        calls.put("threadUncaught", () -> Hooks.threadUncaught(Thread.currentThread(), hostile));
        calls.put("threadUncaught(null)", () -> Hooks.threadUncaught(null, null));
        calls.put("threadExit(null)", () -> Hooks.threadExit(null));

        String name = name("hostile");
        List<String> problems = new CopyOnWriteArrayList<>();
        Recording recording = TracedJvm.record(name, () -> {
            ThreadState ts = ThreadState.current();
            for (Map.Entry<String, Runnable> call : calls.entrySet()) {
                try {
                    call.getValue().run();
                } catch (Throwable e) {
                    problems.add(call.getKey() + " threw " + e);
                }
                if (ts.inHook) problems.add(call.getKey() + " left the thread inside a hook: every later hook on it would be blind");
                if (ts.blockDepth != 0) problems.add(call.getKey() + " left a blocking call open");
                if (ts.awaited) problems.add(call.getKey() + " left its step at the gate unsettled");
                ts.inHook = false;
                ts.blockDepth = 0;
            }
        });
        assertEquals(List.of(), problems);

        // What could be said was said: the exception whose every method throws is still an exception that was caught
        // and one the thread died of, by its class.
        long thread = recording.node(name).getId();
        List<String> classes = new ArrayList<>();
        for (Event event : recording.of(thread)) {
            if (event.getException() != null) classes.add(event.getKind() + " " + event.getException().getClassName() + " '" + event.getException().getMessage() + "'");
        }
        String hostileClass = hostile.getClass().getName();
        assertEquals(List.of(
            "EXCEPTION_HANDLED " + hostileClass + " ''",
            "EXCEPTION_THROWN " + hostileClass + " ''",
            "EXCEPTION_HANDLED " + hostileClass + " ''"), classes);
    }

    // ------------------------------------------------------------------ threads: lifecycle

    @Test
    void aThreadStartedUnderObservationIsLaunchedOnceByItsStarterAndFinishedOnceByItself() throws Exception {
        String starterName = name("starter"), childName = name("child");
        AtomicLong childTid = new AtomicLong();
        Recording recording = TracedJvm.record(starterName, () -> {
            Thread child = new Thread(() -> {
                App.blocking(Wire.BLOCK_SLEEP, NOTHING);
                Hooks.threadExit(Thread.currentThread());
                Hooks.threadExit(Thread.currentThread()); // however often the JVM comes by
            }, childName);
            child.setDaemon(false);
            childTid.set(child.threadId());
            App.start(child);
            App.start(child); // a virtual thread passes through two instrumented start methods
            child.start();
            child.join();
        });

        NodeInfo starter = recording.node(starterName);
        NodeInfo child = recording.node(childName);
        assertEquals(List.of("DISCOVERED"), recording.story(starter.getId()));
        assertEquals(List.of("LAUNCHED", "THREAD_BLOCKED(SLEEP)", "THREAD_UNBLOCKED", "FINISHED(COMPLETED)"), recording.story(child.getId()));
        assertEquals(List.of(), recording.errors());

        assertEquals(NodeKind.THREAD, child.getKind());
        assertEquals(starter.getId(), child.getParentId(), "the structural parent of a thread is the thread that started it");
        assertEquals(starter.getId(), child.getCreatorId());
        assertEquals("java.lang.Thread", child.getImplClass());
        assertEquals(childTid.get(), child.getThread().getTid());
        assertFalse(child.getThread().getDaemon());
        assertEquals("com.acme.app.App", recording.stackFrames.get(child.getSiteFrame()).getClassName(), "the innermost frame outside the runtimes");

        List<Event> events = recording.of(child.getId());
        assertEquals(starter.getId(), events.get(0).getThreadId(), "launched: captured on the starting thread");
        assertEquals(child.getId(), events.get(3).getThreadId(), "finished: on the thread that ends");
    }

    @Test
    void aThreadInterruptedBeforeItIsStartedIsReferredToFirstAndDefinedByItsStart() throws Exception {
        String starterName = name("starter"), lateName = name("interrupted-before-start");
        Recording recording = TracedJvm.record(starterName, () -> {
            Thread late = new Thread(NOTHING, lateName);
            App.interrupt(late); // legal, and it sets the flag the thread starts with
            App.start(late);
        });

        NodeInfo starter = recording.node(starterName);
        NodeInfo late = recording.node(lateName);
        List<Event> events = recording.of(late.getId());
        assertEquals(List.of("THREAD_INTERRUPTED", "LAUNCHED"), recording.story(late.getId()), "one node, no second definition");
        assertNull(events.get(0).getNode());
        assertEquals(starter.getId(), events.get(0).getOtherNodeId(), "the interrupter");
        assertEquals("com.acme.app.App.interrupt", recording.stack(events.get(0).getStack()).get(0));
        assertNotNull(events.get(1).getNode());
        assertEquals(List.of(), recording.errors());
    }

    @Test
    void aRunningThreadNobodySawStartIsDiscoveredByWhoeverTouchesItFirst() throws Exception {
        String interrupterName = name("interrupter"), bystanderName = name("bystander");
        CountDownLatch running = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread bystander = new Thread(() -> {
            running.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                // the test is over
            }
        }, bystanderName);
        bystander.setDaemon(true);
        bystander.start(); // before tracing is on, and it calls no hook itself
        assertTrue(running.await(10, TimeUnit.SECONDS));
        try {
            Recording recording = TracedJvm.record(interrupterName, () -> {
                App.interrupt(bystander);
                App.interrupt(bystander);
            });
            NodeInfo interrupter = recording.node(interrupterName);
            NodeInfo node = recording.node(bystanderName);
            assertEquals(List.of("DISCOVERED", "THREAD_INTERRUPTED", "THREAD_INTERRUPTED"), recording.story(node.getId()));
            Event discovered = recording.of(node.getId()).get(0);
            assertEquals(interrupter.getId(), discovered.getThreadId(), "the event was captured on the thread that came across it");
            assertEquals(Origin.LIBRARY, node.getOrigin());
            assertEquals(0, node.getParentId(), "nobody knows who started it");
            assertEquals(List.of(), recording.errors());
        } finally {
            release.countDown();
        }
    }

    @Test
    void aThreadThatDiesOfAnExceptionSaysThrownAndHandledInOneStepAndFinishesFailed() throws Exception {
        String name = name("dying");
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Recording recording = TracedJvm.record(name, () -> {
            RuntimeException failure = App.failure("boom");
            thrown.set(failure);
            Hooks.threadUncaught(Thread.currentThread(), failure);
            Hooks.threadExit(Thread.currentThread());
        });

        long thread = recording.node(name).getId();
        assertEquals(List.of("DISCOVERED", "EXCEPTION_THROWN", "EXCEPTION_HANDLED(UNCAUGHT_EXCEPTION_HANDLER)", "FINISHED(FAILED)"), recording.story(thread));
        List<Event> events = recording.of(thread);
        Event thrownEvent = events.get(1), handled = events.get(2);
        assertEquals("java.lang.IllegalStateException", thrownEvent.getException().getClassName());
        assertEquals("boom", thrownEvent.getException().getMessage());
        assertFalse(thrownEvent.getException().getCancellation());
        assertEquals(System.identityHashCode(thrown.get()), thrownEvent.getException().getIdentity());
        assertEquals("com.acme.app.App.failure", recording.stack(thrownEvent.getException().getStack()).get(0), "where it was thrown");
        assertEquals(thrownEvent.getException().getIdentity(), handled.getException().getIdentity(), "one instance, followed across events");
        assertEquals(List.of(), handled.getException().getStack(), "the throwable's stack is only on thrown and on a catch");
        assertFalse(thrownEvent.getSameStep());
        assertTrue(handled.getSameStep(), "no program code between the two: one step, never spread out");
        assertEquals(0, handled.getHeldNanos());
        assertEquals(List.of(), recording.errors());
    }

    @Test
    void aThreadThatNeverDidAnythingTheAgentSawEndsWithoutANode() throws Exception {
        Recording recording = TracedJvm.record(name("unseen"), () -> Hooks.threadExit(Thread.currentThread()));
        assertEquals(List.of(), recording.events);
        assertEquals(List.of(), recording.errors());
    }

    @Test
    void anExceptionCaughtByProjectCodeIsHandledByCatchWithBothStacksCutToTheConfiguredDepth() throws Exception {
        String name = name("catcher");
        Recording recording = TracedJvm.record(name, () -> {
            RuntimeException[] failure = new RuntimeException[1];
            App.deep(40, () -> failure[0] = App.failure("deep"));
            App.deep(40, () -> App.catches(failure[0]));
        });

        long thread = recording.node(name).getId();
        assertEquals(List.of("DISCOVERED", "EXCEPTION_HANDLED(CATCH)"), recording.story(thread));
        Event caught = recording.of(thread).get(1);
        List<String> caughtAt = recording.stack(caught.getStack()), thrownAt = recording.stack(caught.getException().getStack());
        assertEquals(TracedJvm.STACK_DEPTH, caughtAt.size());
        assertEquals(TracedJvm.STACK_DEPTH, thrownAt.size());
        assertEquals("com.acme.app.App.catches", caughtAt.get(0));
        assertEquals("com.acme.app.App.failure", thrownAt.get(0));
        assertEquals(EventKind.EXCEPTION_HANDLED, caught.getKind());
        assertEquals("deep", caught.getException().getMessage());
        assertEquals(List.of(), recording.errors());
    }

    // ------------------------------------------------------------------ pools

    @Test
    void theFirstWorkerOfAPoolBringsThePoolAlongAndEveryWorkerHangsUnderIt() throws Exception {
        ForkJoinPool pool = new ForkJoinPool(2);
        CountDownLatch running = new CountDownLatch(2), release = new CountDownLatch(1);
        List<Thread> workers = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 2; i++) {
            pool.execute(() -> {
                workers.add(Thread.currentThread());
                running.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    // the test is over
                }
            });
        }
        try {
            assertTrue(running.await(10, TimeUnit.SECONDS), "two workers of the pool");
            String starterName = name("pool-user");
            Recording recording = TracedJvm.record(starterName, () -> {
                for (Thread worker : workers) App.start(worker);
            });

            NodeInfo starter = recording.node(starterName);
            List<String> launched = new ArrayList<>();
            NodeInfo poolNode = null;
            for (Event event : recording.events) {
                if (event.getKind() != EventKind.LAUNCHED) continue;
                launched.add(event.getNode().getKind().name());
                if (event.getNode().getKind() == NodeKind.POOL) poolNode = event.getNode();
                assertEquals(starter.getId(), event.getThreadId());
            }
            assertEquals(List.of("POOL", "THREAD", "THREAD"), launched, "the pool once, before its first worker");
            assertEquals("ForkJoinPool", poolNode.getName());
            assertEquals("java.util.concurrent.ForkJoinPool", poolNode.getImplClass());
            assertEquals(Origin.LIBRARY, poolNode.getOrigin());
            assertEquals(0, poolNode.getParentId());
            assertEquals(starter.getId(), poolNode.getCreatorId());
            assertNull(poolNode.getThread());
            for (Thread worker : workers) {
                NodeInfo node = recording.node(worker.getName());
                assertEquals(poolNode.getId(), node.getParentId(), "the owning pool, not whichever thread made it grow");
                assertEquals("worker", node.getConstruct());
                assertEquals(Origin.LIBRARY, node.getOrigin());
                assertEquals(0, node.getSiteFrame(), "the line the pool happened to grow at is an accident");
                assertEquals(starter.getId(), node.getCreatorId());
            }
            assertEquals(List.of(), recording.errors());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
