/*
 * Spike, option (b): monitor contention through JVMTI.
 *
 * The four monitor events are delivered synchronously on the thread they happen to, and JNI is allowed inside them,
 * so each callback calls a static Java method right there. In the real agent that method would be
 * Hooks.blockEnter(MONITOR) / Hooks.blockExit(): the event gets its place in the global sequence at the moment it
 * happens, like every bytecode-instrumented hook.
 */
#include <jvmti.h>
#include <string.h>

static jclass sink_class;
static jmethodID sink_event;

enum { CONTENDED_ENTER = 1, CONTENDED_ENTERED = 2, WAIT = 3, WAITED = 4 };

static void report(JNIEnv *jni, jint kind, jobject monitor) {
    if (sink_class == NULL) return; /* before VMInit, or the sink is not on the class path */
    (*jni)->CallStaticVoidMethod(jni, sink_class, sink_event, kind, monitor);
    if ((*jni)->ExceptionCheck(jni)) {
        (*jni)->ExceptionDescribe(jni); /* a spike wants to know; the real agent would swallow it inside the hook */
        (*jni)->ExceptionClear(jni);
    }
}

static void JNICALL on_contended_enter(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread, jobject object) {
    report(jni, CONTENDED_ENTER, object);
}

static void JNICALL on_contended_entered(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread, jobject object) {
    report(jni, CONTENDED_ENTERED, object);
}

static void JNICALL on_wait(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread, jobject object, jlong timeout) {
    report(jni, WAIT, object);
}

static void JNICALL on_waited(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread, jobject object, jboolean timed_out) {
    report(jni, WAITED, object);
}

static void JNICALL on_vm_init(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread) {
    jclass local = (*jni)->FindClass(jni, "spike/JvmtiSpike$Sink");
    if (local == NULL) {
        (*jni)->ExceptionClear(jni);
        return;
    }
    sink_event = (*jni)->GetStaticMethodID(jni, local, "event", "(ILjava/lang/Object;)V");
    if (sink_event == NULL) {
        (*jni)->ExceptionClear(jni);
        return;
    }
    sink_class = (*jni)->NewGlobalRef(jni, local);

    (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_MONITOR_CONTENDED_ENTER, NULL);
    (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_MONITOR_CONTENDED_ENTERED, NULL);
    (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_MONITOR_WAIT, NULL);
    (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_MONITOR_WAITED, NULL);
}

static jint setup(JavaVM *vm, jvmtiEnv **out);

/*
 * The other way in: System.load() from Java in a running JVM (which is what a -javaagent premain could do with a
 * library unpacked from its jar, so that users never see an -agentpath). The question it answers: can the monitor
 * capability still be acquired in the live phase? VMInit is long past, so the sink is resolved right here.
 */
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    jvmtiEnv *jvmti;
    JNIEnv *jni;
    if (setup(vm, &jvmti) != JNI_OK) return JNI_ERR;
    if ((*vm)->GetEnv(vm, (void **) &jni, JNI_VERSION_1_8) != JNI_OK) return JNI_ERR;
    on_vm_init(jvmti, jni, NULL);
    return sink_class != NULL ? JNI_VERSION_1_8 : JNI_ERR;
}

JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *vm, char *options, void *reserved) {
    jvmtiEnv *jvmti;
    if (setup(vm, &jvmti) != JNI_OK) return JNI_ERR;
    return (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_VM_INIT, NULL) == JVMTI_ERROR_NONE ? JNI_OK : JNI_ERR;
}

static jint setup(JavaVM *vm, jvmtiEnv **out) {
    jvmtiEnv *jvmti;
    if ((*vm)->GetEnv(vm, (void **) &jvmti, JVMTI_VERSION_1_2) != JNI_OK) return JNI_ERR;
    *out = jvmti;

    jvmtiCapabilities capabilities;
    memset(&capabilities, 0, sizeof(capabilities));
    capabilities.can_generate_monitor_events = 1;
    if ((*jvmti)->AddCapabilities(jvmti, &capabilities) != JVMTI_ERROR_NONE) return JNI_ERR;

    jvmtiEventCallbacks callbacks;
    memset(&callbacks, 0, sizeof(callbacks));
    callbacks.VMInit = on_vm_init;
    callbacks.MonitorContendedEnter = on_contended_enter;
    callbacks.MonitorContendedEntered = on_contended_entered;
    callbacks.MonitorWait = on_wait;
    callbacks.MonitorWaited = on_waited;
    if ((*jvmti)->SetEventCallbacks(jvmti, &callbacks, sizeof(callbacks)) != JVMTI_ERROR_NONE) return JNI_ERR;
    return JNI_OK;
}
