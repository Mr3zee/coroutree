package kotlinx.coroutree.agent;

import kotlinx.coroutree.agent.MethodPatch.Arg;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** The values a hook call can be given. Each pushes exactly one value. */
final class Args {
    private Args() {}

    static Arg self() {
        return (code, context, exitOpcode) -> code.add(new VarInsnNode(Opcodes.ALOAD, 0));
    }

    /** The method's {@code index}-th declared parameter. */
    static Arg arg(int index) {
        return (code, context, exitOpcode) ->
            code.add(new VarInsnNode(context.argType(index).getOpcode(Opcodes.ILOAD), context.argSlot(index)));
    }

    static Arg constant(int value) {
        return (code, context, exitOpcode) -> code.add(new LdcInsnNode(value));
    }

    /** A field of {@code this}; {@code owner} is the class that declares it. */
    static Arg field(String owner, String name, String descriptor) {
        return (code, context, exitOpcode) -> {
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, name, descriptor));
        };
    }

    /** Result of a no-argument method called on the value pushed by {@code receiver}. */
    static Arg call(Arg receiver, int invokeOpcode, String owner, String name, String descriptor) {
        return (code, context, exitOpcode) -> {
            receiver.push(code, context, exitOpcode);
            code.add(new MethodInsnNode(invokeOpcode, owner, name, descriptor, invokeOpcode == Opcodes.INVOKEINTERFACE));
        };
    }

    /**
     * The value about to be returned, which is on top of the stack at that point. Only for single-slot return types,
     * only as the first argument, and only in a plain exit patch: there is no value when leaving by exception.
     */
    static Arg returnValue() {
        return (code, context, exitOpcode) -> {
            if (exitOpcode != Opcodes.IRETURN && exitOpcode != Opcodes.ARETURN && exitOpcode != Opcodes.FRETURN) {
                throw new IllegalStateException("returnValue() before opcode " + exitOpcode);
            }
            code.add(new InsnNode(Opcodes.DUP));
        };
    }
}
