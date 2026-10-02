package kotlinx.coroutree.agent;

import kotlinx.coroutree.runtime.AgentConfig;
import kotlinx.coroutree.runtime.Hooks;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

/**
 * Where rewritten classes are tried out: a throwaway class loader that defines the bytes it is given under their own
 * names — the JVM verifies them like any class of an application, against stack map frames nobody recomputed — next
 * to a {@code kotlinx.coroutree.runtime.Hooks} of its own, which has every hook of the real one and does nothing but
 * write the call down in {@link HookLog}.
 */
final class Lab {
    private Lab() {}

    static final String HOOKS = "kotlinx.coroutree.runtime.Hooks";

    static CoroutreeTransformer transformer(String agentArgs) {
        return new CoroutreeTransformer(new HookTable(), AgentConfig.parse(agentArgs));
    }

    static String internalName(Class<?> type) {
        return type.getName().replace('.', '/');
    }

    /** The class file a class of the test class path was loaded from. */
    static byte[] bytesOf(Class<?> type) {
        return bytesOf(type.getClassLoader(), internalName(type));
    }

    static byte[] bytesOf(ClassLoader loader, String internalName) {
        try (InputStream in = loader == null ? ClassLoader.getSystemResourceAsStream(internalName + ".class") : loader.getResourceAsStream(internalName + ".class")) {
            if (in == null) throw new AssertionError("no class file of " + internalName);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static final class Loader extends ClassLoader {
        private final Map<String, byte[]> classes = new HashMap<>();

        Loader() {
            super(Lab.class.getClassLoader());
            classes.put(HOOKS, recordingHooks());
        }

        Loader with(Class<?> original, byte[] rewritten) {
            return with(original.getName(), rewritten);
        }

        Loader with(String name, byte[] rewritten) {
            if (rewritten == null) throw new AssertionError(name + " was not rewritten");
            classes.put(name, rewritten);
            return this;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                byte[] bytes = classes.get(name);
                if (bytes == null) return super.loadClass(name, resolve);
                Class<?> loaded = findLoadedClass(name);
                return loaded != null ? loaded : defineClass(name, bytes, 0, bytes.length);
            }
        }

        /** The class, linked and initialised: a class the verifier rejects fails here. */
        Class<?> verified(String name) {
            try {
                return Class.forName(name, true, this);
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
        }

        Class<?> verified(Class<?> original) {
            return verified(original.getName());
        }
    }

    /** The outcome of a call: what it returned, or what it threw. */
    static final class Outcome {
        final Object value;
        final Throwable thrown;

        Outcome(Object value, Throwable thrown) {
            this.value = value;
            this.thrown = thrown;
        }

        @Override
        public String toString() {
            return thrown != null ? "threw " + thrown.getClass().getName() + ": " + thrown.getMessage() : "returned " + value;
        }
    }

    /** Calls the public method of that name (the only one) on {@code target}, or statically on a class. */
    static Outcome call(Object target, String method, Object... arguments) {
        Class<?> type = target instanceof Class<?> c ? c : target.getClass();
        Method found = null;
        for (Method candidate : type.getMethods()) {
            if (!candidate.getName().equals(method)) continue;
            if (found != null) throw new AssertionError("two methods named " + method);
            found = candidate;
        }
        if (found == null) throw new AssertionError("no method " + method + " in " + type);
        try {
            return new Outcome(found.invoke(Modifier.isStatic(found.getModifiers()) ? null : target, arguments), null);
        } catch (InvocationTargetException e) {
            return new Outcome(null, e.getCause());
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    private static byte[] recordingHooks() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, HOOKS.replace('.', '/'), null, "java/lang/Object", null);
        for (Method hook : Hooks.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(hook.getModifiers()) || !Modifier.isStatic(hook.getModifiers())) continue;
            MethodVisitor code = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, hook.getName(), Type.getMethodDescriptor(hook), null, null);
            Type[] parameters = Type.getArgumentTypes(hook);
            code.visitCode();
            code.visitLdcInsn(hook.getName());
            code.visitLdcInsn(parameters.length);
            code.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
            int slot = 0;
            for (int i = 0; i < parameters.length; i++) {
                code.visitInsn(Opcodes.DUP);
                code.visitLdcInsn(i);
                code.visitVarInsn(parameters[i].getOpcode(Opcodes.ILOAD), slot);
                switch (parameters[i].getSort()) {
                    case Type.INT -> code.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
                    case Type.BOOLEAN -> code.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
                    case Type.OBJECT, Type.ARRAY -> {
                    }
                    default -> throw new AssertionError("a hook takes a " + parameters[i] + ": teach the stand-in to box it");
                }
                code.visitInsn(Opcodes.AASTORE);
                slot += parameters[i].getSize();
            }
            code.visitMethodInsn(Opcodes.INVOKESTATIC, internalName(HookLog.class), "record", "(Ljava/lang/String;[Ljava/lang/Object;)V", false);
            code.visitInsn(Opcodes.RETURN);
            code.visitMaxs(0, 0);
            code.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }
}
