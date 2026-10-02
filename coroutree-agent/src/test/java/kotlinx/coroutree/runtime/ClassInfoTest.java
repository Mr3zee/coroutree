package kotlinx.coroutree.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What the class of a job says about the node it becomes (DESIGN §2.1), read off the class hierarchy of the
 * kotlinx.coroutines the project is built with: these are the library's internal classes, by the names the agent
 * knows them under.
 */
class ClassInfoTest {
    private static String info(String className) throws ClassNotFoundException {
        ClassInfo info = ClassInfo.of(Class.forName(className, false, ClassInfoTest.class.getClassLoader()));
        String kind = switch (info.kind) {
            case Wire.KIND_COROUTINE -> "COROUTINE";
            case Wire.KIND_SCOPE -> "SCOPE";
            case Wire.KIND_CONTEXT_CHANGE -> "CONTEXT_CHANGE";
            default -> "kind " + info.kind;
        };
        return kind + " " + info.construct
            + (info.scoped ? ", throws at its caller" : "")
            + (info.startsUndispatched ? ", starts undispatched" : "")
            + (info.deferred ? ", holds its exception" : "")
            + (info.blocking ? ", blocks its thread" : "")
            + (info.constructionHooked ? "" : ", construction not seen");
    }

    @Test
    void theClassOfAJobSaysWhatKindOfNodeItIsAndHowItBehaves() throws Exception {
        String[][] expected = {
            // What runs code of its own, concurrently with its parent.
            {"kotlinx.coroutines.StandaloneCoroutine", "COROUTINE launch"},
            {"kotlinx.coroutines.LazyStandaloneCoroutine", "COROUTINE launch"},
            {"kotlinx.coroutines.DeferredCoroutine", "COROUTINE async, holds its exception"},
            {"kotlinx.coroutines.LazyDeferredCoroutine", "COROUTINE async, holds its exception"},
            {"kotlinx.coroutines.BlockingCoroutine", "COROUTINE runBlocking, blocks its thread"},
            // What runs in its caller's place, on its caller's thread until it suspends, and throws its failure at the caller.
            {"kotlinx.coroutines.internal.ScopeCoroutine", "SCOPE coroutineScope, throws at its caller, starts undispatched"},
            {"kotlinx.coroutines.SupervisorCoroutine", "SCOPE supervisorScope, throws at its caller, starts undispatched"},
            {"kotlinx.coroutines.TimeoutCoroutine", "SCOPE withTimeout, throws at its caller, starts undispatched"},
            // withContext: to another dispatcher it is dispatched, like any coroutine; otherwise it starts in place.
            {"kotlinx.coroutines.DispatchedCoroutine", "CONTEXT_CHANGE withContext, throws at its caller"},
            {"kotlinx.coroutines.UndispatchedCoroutine", "CONTEXT_CHANGE withContext, throws at its caller, starts undispatched"},
            // A plain Job: nothing runs in it, it only holds children.
            {"kotlinx.coroutines.JobImpl", "SCOPE Job()"},
            {"kotlinx.coroutines.SupervisorJobImpl", "SCOPE SupervisorJob()"},
            // A job of a class whose constructor is not hooked: found when something happens to it, named after its class.
            {"kotlinx.coroutines.CompletableDeferredImpl", "SCOPE CompletableDeferredImpl, construction not seen"},
        };
        for (String[] row : expected) assertEquals(row[1], info(row[0]), row[0]);
    }
}
