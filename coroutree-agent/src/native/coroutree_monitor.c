/*
 * coroutree monitor probe: the one thing bytecode instrumentation cannot see, a thread blocking on `synchronized`.
 *
 * JVMTI delivers MonitorContendedEnter / MonitorContendedEntered on the contending thread, at the moment it happens,
 * and JNI is allowed inside the callbacks. So they call Hooks.blockEnter(MONITOR) / Hooks.blockExit() right there, and
 * a contended monitor becomes a THREAD_BLOCKED like any other, with its place in the global sequence. Everything else
 * (what it means, nesting, re-entrancy, the agent's own threads) is decided in Java; see docs/spikes/monitor-contention.md
 * for why this and not JFR.
 *
 * Two ways in, same result:
 *  - `-agentpath:` (what the Gradle plugin does). The library is loaded before the Java agent exists, so it waits for
 *    kotlinx.coroutree.runtime.MonitorProbe to be prepared and registers that class's native method on it.
 *  - System.load() from the Java agent, when nobody passed -agentpath. Works in the live phase because
 *    can_generate_monitor_events can still be acquired then; costs a native-access warning on JDK 24+.
 * Either way nothing is reported until Java calls MonitorProbe.enable(Hooks.class).
 */
#include <jvmti.h>
#include <stdint.h>
#include <string.h>

#define PROBE_CLASS_SIGNATURE "Lkotlinx/coroutree/runtime/MonitorProbe;"
#define BLOCK_MONITOR 1 /* Wire.BLOCK_MONITOR */

static jvmtiEnv *jvmti_env;
static jclass hooks_class;
static jmethodID block_enter;
static jmethodID block_exit;

/*
 * Thread-local storage holds the depth of contended enters that were reported and not yet matched, as an integer.
 *
 * Depth, not a flag, because the callbacks nest: blockEnter is Java code, and Java code may itself run into a
 * contended monitor (a bin of a ConcurrentHashMap is enough) while the monitor that started it all is still being
 * waited for. With a flag, the inner "entered" cleared it and the outer one went without its blockExit: the thread's
 * books said "blocked" for the rest of its life and none of its blocking calls was reported again. Only the outermost
 * pair calls into Java. The inner ones happen inside our own hook, which would not report them anyway.
 *
 * (A thread that is held at the agent's gate is held inside blockEnter or blockExit, that is, inside these callbacks,
 * for as long as the user likes. Nothing here minds: no lock is held across the call, and the depth is the thread's own.)
 */
static void JNICALL on_contended_enter(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread, jobject monitor) {
    void *depth = NULL;
    if (hooks_class == NULL || (*jni)->ExceptionCheck(jni)) return;
    if ((*jvmti)->GetThreadLocalStorage(jvmti, thread, &depth) != JVMTI_ERROR_NONE) return;
    if ((*jvmti)->SetThreadLocalStorage(jvmti, thread, (void *) ((intptr_t) depth + 1)) != JVMTI_ERROR_NONE) return;
    if (depth != NULL) return;
    (*jni)->CallStaticVoidMethod(jni, hooks_class, block_enter, (jint) BLOCK_MONITOR);
    if ((*jni)->ExceptionCheck(jni)) (*jni)->ExceptionClear(jni); /* hooks do not throw; belt and braces */
}

static void JNICALL on_contended_entered(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread, jobject monitor) {
    void *depth = NULL;
    if (hooks_class == NULL) return;
    if ((*jvmti)->GetThreadLocalStorage(jvmti, thread, &depth) != JVMTI_ERROR_NONE || depth == NULL) return;
    (*jvmti)->SetThreadLocalStorage(jvmti, thread, (void *) ((intptr_t) depth - 1));
    if ((intptr_t) depth != 1) return;
    /* The exit is owed whatever else is going on: an enter without it leaves the thread "blocked" for good. */
    jthrowable pending = (*jni)->ExceptionOccurred(jni);
    if (pending != NULL) (*jni)->ExceptionClear(jni);
    (*jni)->CallStaticVoidMethod(jni, hooks_class, block_exit);
    if ((*jni)->ExceptionCheck(jni)) (*jni)->ExceptionClear(jni);
    if (pending != NULL) (*jni)->Throw(jni, pending);
}

/* boolean MonitorProbe.enable(Class hooks) */
static jboolean JNICALL probe_enable(JNIEnv *jni, jclass probe, jclass hooks) {
    if (jvmti_env == NULL || hooks == NULL) return JNI_FALSE;
    if (hooks_class != NULL) return JNI_TRUE;
    block_enter = (*jni)->GetStaticMethodID(jni, hooks, "blockEnter", "(I)V");
    block_exit = block_enter == NULL ? NULL : (*jni)->GetStaticMethodID(jni, hooks, "blockExit", "()V");
    if (block_exit == NULL) {
        (*jni)->ExceptionClear(jni);
        return JNI_FALSE;
    }
    if ((*jvmti_env)->SetEventNotificationMode(jvmti_env, JVMTI_ENABLE, JVMTI_EVENT_MONITOR_CONTENDED_ENTER, NULL) != JVMTI_ERROR_NONE
        || (*jvmti_env)->SetEventNotificationMode(jvmti_env, JVMTI_ENABLE, JVMTI_EVENT_MONITOR_CONTENDED_ENTERED, NULL) != JVMTI_ERROR_NONE) {
        return JNI_FALSE;
    }
    hooks_class = (*jni)->NewGlobalRef(jni, hooks); /* last: the callbacks take it as "ready" */
    return hooks_class != NULL ? JNI_TRUE : JNI_FALSE;
}

/* The name JNI looks for when the library came through System.load(). */
JNIEXPORT jboolean JNICALL Java_kotlinx_coroutree_runtime_MonitorProbe_enable(JNIEnv *jni, jclass probe, jclass hooks) {
    return probe_enable(jni, probe, hooks);
}

/* -agentpath only: JNI does not look into agent libraries for native methods, so the method is registered by hand. */
static void JNICALL on_class_prepare(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread, jclass klass) {
    char *signature = NULL;
    if ((*jvmti)->GetClassSignature(jvmti, klass, &signature, NULL) != JVMTI_ERROR_NONE) return;
    int found = strcmp(signature, PROBE_CLASS_SIGNATURE) == 0;
    (*jvmti)->Deallocate(jvmti, (unsigned char *) signature);
    if (!found) return;

    JNINativeMethod method = {(char *) "enable", (char *) "(Ljava/lang/Class;)Z", (void *) probe_enable};
    if ((*jni)->RegisterNatives(jni, klass, &method, 1) != JNI_OK) (*jni)->ExceptionClear(jni);
    (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_DISABLE, JVMTI_EVENT_CLASS_PREPARE, NULL);
}

static jint setup(JavaVM *vm, int wait_for_probe_class) {
    jvmtiEnv *jvmti;
    jvmtiCapabilities capabilities;
    jvmtiEventCallbacks callbacks;

    if (jvmti_env != NULL) return JNI_OK; /* loaded both ways: the first one did the work */
    if ((*vm)->GetEnv(vm, (void **) &jvmti, JVMTI_VERSION_1_2) != JNI_OK) return JNI_ERR;

    memset(&capabilities, 0, sizeof(capabilities));
    capabilities.can_generate_monitor_events = 1;
    if ((*jvmti)->AddCapabilities(jvmti, &capabilities) != JVMTI_ERROR_NONE) return JNI_ERR;

    memset(&callbacks, 0, sizeof(callbacks));
    callbacks.MonitorContendedEnter = on_contended_enter;
    callbacks.MonitorContendedEntered = on_contended_entered;
    callbacks.ClassPrepare = on_class_prepare;
    if ((*jvmti)->SetEventCallbacks(jvmti, &callbacks, sizeof(callbacks)) != JVMTI_ERROR_NONE) return JNI_ERR;

    if (wait_for_probe_class
        && (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_CLASS_PREPARE, NULL) != JVMTI_ERROR_NONE) {
        return JNI_ERR;
    }
    jvmti_env = jvmti;
    return JNI_OK;
}

JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *vm, char *options, void *reserved) {
    /* Never fail the JVM over this: without the probe there is simply no MONITOR reason. */
    setup(vm, 1);
    return JNI_OK;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    return setup(vm, 0) == JNI_OK ? JNI_VERSION_1_8 : JNI_ERR;
}
