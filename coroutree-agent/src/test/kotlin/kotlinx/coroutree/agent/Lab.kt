package kotlinx.coroutree.agent

import kotlinx.coroutree.runtime.AgentConfig
import kotlinx.coroutree.runtime.Hooks
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier

/**
 * A throwaway class loader for rewritten classes. It defines the bytes it is given under their own names. Next to
 * them it defines a `kotlinx.coroutree.runtime.Hooks` of its own, which has every hook of the real one and only
 * records the call in [HookLog]. The JVM verifies the classes against the stack map frames the transformer left,
 * which nobody recomputed.
 */
internal object Lab {
    const val HOOKS = "kotlinx.coroutree.runtime.Hooks"

    fun transformer(agentArgs: String): CoroutreeTransformer = CoroutreeTransformer(HookTable(), AgentConfig.parse(agentArgs))

    fun internalName(type: Class<*>): String = type.name.replace('.', '/')

    /** The class file a class of the test class path was loaded from. */
    fun bytesOf(type: Class<*>): ByteArray = bytesOf(type.classLoader, internalName(type))

    fun bytesOf(loader: ClassLoader?, internalName: String): ByteArray {
        val resource = "$internalName.class"
        val stream = if (loader == null) ClassLoader.getSystemResourceAsStream(resource) else loader.getResourceAsStream(resource)
        return (stream ?: throw AssertionError("no class file of $internalName")).use { it.readAllBytes() }
    }

    /** Calls the one public method named `method` on `target`, or statically when `target` is a class. */
    fun call(target: Any, method: String, vararg arguments: Any?): Outcome {
        val type = target as? Class<*> ?: target.javaClass
        val candidates = type.methods.filter { it.name == method }
        if (candidates.size > 1) throw AssertionError("two methods named $method")
        val found = candidates.firstOrNull() ?: throw AssertionError("no method $method in $type")
        return try {
            Outcome(found.invoke(if (Modifier.isStatic(found.modifiers)) null else target, *arguments), null)
        } catch (e: InvocationTargetException) {
            Outcome(null, e.cause)
        }
    }

    private fun recordingHooks(): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, HOOKS.replace('.', '/'), null, "java/lang/Object", null)
        for (hook in Hooks::class.java.declaredMethods) {
            if (!Modifier.isPublic(hook.modifiers) || !Modifier.isStatic(hook.modifiers)) continue
            val code = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, hook.name, Type.getMethodDescriptor(hook), null, null)
            val parameters = Type.getArgumentTypes(hook)
            code.visitCode()
            code.visitLdcInsn(hook.name)
            code.visitLdcInsn(parameters.size)
            code.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object")
            var slot = 0
            for ((i, parameter) in parameters.withIndex()) {
                code.visitInsn(Opcodes.DUP)
                code.visitLdcInsn(i)
                code.visitVarInsn(parameter.getOpcode(Opcodes.ILOAD), slot)
                when (parameter.sort) {
                    Type.INT -> code.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false)
                    Type.BOOLEAN -> code.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false)
                    Type.OBJECT, Type.ARRAY -> {}
                    else -> throw AssertionError("a hook takes a $parameter: teach the stand-in to box it")
                }
                code.visitInsn(Opcodes.AASTORE)
                slot += parameter.size
            }
            code.visitMethodInsn(Opcodes.INVOKESTATIC, internalName(HookLog::class.java), "record", "(Ljava/lang/String;[Ljava/lang/Object;)V", false)
            code.visitInsn(Opcodes.RETURN)
            code.visitMaxs(0, 0)
            code.visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    class Loader : ClassLoader(Lab::class.java.classLoader) {
        private val classes = HashMap<String, ByteArray>()

        init {
            classes[HOOKS] = recordingHooks()
        }

        fun with(original: Class<*>, rewritten: ByteArray?): Loader = with(original.name, rewritten)

        fun with(name: String, rewritten: ByteArray?): Loader {
            classes[name] = rewritten ?: throw AssertionError("$name was not rewritten")
            return this
        }

        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            synchronized(getClassLoadingLock(name)) {
                val bytes = classes[name] ?: return super.loadClass(name, resolve)
                return findLoadedClass(name) ?: defineClass(name, bytes, 0, bytes.size)
            }
        }

        /** The class, linked and initialized. A class that the verifier rejects fails here. */
        fun verified(name: String): Class<*> = Class.forName(name, true, this)

        fun verified(original: Class<*>): Class<*> = verified(original.name)
    }

    class Outcome(val value: Any?, val thrown: Throwable?) {
        override fun toString(): String =
            if (thrown != null) "threw ${thrown.javaClass.name}: ${thrown.message}" else "returned $value"
    }
}
