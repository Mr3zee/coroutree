package kotlinx.coroutree.runtime;

/** What the class of a job says about the node it becomes. Computed once per class. */
final class ClassInfo {
    private static final ClassValue<ClassInfo> CACHE = new ClassValue<ClassInfo>() {
        @Override
        protected ClassInfo computeValue(Class<?> type) {
            return new ClassInfo(type);
        }
    };

    static ClassInfo of(Class<?> type) {
        return CACHE.get(type);
    }

    final int kind;
    /** Construct name to use when the creating stack does not tell. */
    final String construct;
    final boolean startsUndispatched;
    /**
     * A ScopeCoroutine: coroutineScope, withContext (to another dispatcher too), withTimeout, … It does not report a
     * failure to its parent job ({@code isScopedCoroutine}); it throws it at the code that called it.
     */
    final boolean scoped;
    final boolean deferred;
    final boolean blocking;
    /** Instances are seen being constructed: a node for one always gets, or has got, a LAUNCHED definition. */
    final boolean constructionHooked;

    private ClassInfo(Class<?> type) {
        boolean coroutine = false, scope = false, dispatched = false, undispatched = false, deferred = false, blocking = false, plainJob = false;
        String construct = null;
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            switch (c.getName()) {
                case "kotlinx.coroutines.AbstractCoroutine" -> coroutine = true;
                case "kotlinx.coroutines.internal.ScopeCoroutine" -> scope = true;
                case "kotlinx.coroutines.DispatchedCoroutine" -> dispatched = true;
                case "kotlinx.coroutines.UndispatchedCoroutine" -> undispatched = true;
                case "kotlinx.coroutines.DeferredCoroutine" -> {
                    deferred = true;
                    construct = "async";
                }
                case "kotlinx.coroutines.BlockingCoroutine" -> {
                    blocking = true;
                    construct = "runBlocking";
                }
                case "kotlinx.coroutines.StandaloneCoroutine" -> construct = "launch";
                case "kotlinx.coroutines.SupervisorCoroutine" -> construct = "supervisorScope";
                case "kotlinx.coroutines.TimeoutCoroutine" -> construct = "withTimeout";
                case "kotlinx.coroutines.SupervisorJobImpl" -> construct = "SupervisorJob()";
                case "kotlinx.coroutines.JobImpl" -> {
                    plainJob = true;
                    if (construct == null) construct = "Job()";
                }
                default -> {
                }
            }
        }
        if (dispatched || undispatched) {
            kind = Wire.KIND_CONTEXT_CHANGE;
            if (construct == null) construct = "withContext";
        } else if (scope) {
            kind = Wire.KIND_SCOPE;
            if (construct == null) construct = "coroutineScope";
        } else if (coroutine) {
            kind = Wire.KIND_COROUTINE;
        } else {
            kind = Wire.KIND_SCOPE; // a plain Job: nothing runs in it, it only holds children
        }
        this.construct = construct != null ? construct : Describe.simpleName(type);
        this.startsUndispatched = scope && !dispatched;
        this.scoped = scope;
        this.constructionHooked = coroutine || plainJob;
        this.deferred = deferred;
        this.blocking = blocking;
    }
}
