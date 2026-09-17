package kotlinx.coroutree.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Calls to {@code kotlinx.coroutree.runtime.Hooks} woven into one method: at its start, before its returns, or around
 * it like try/finally.
 *
 * Inserted code is straight-line on purpose. No branches means no new stack map frames inside existing code, so classes
 * are rewritten without recomputing frames — which would need the class hierarchy, i.e. class loading from inside a
 * transformer. The one frame that is needed, at the catch-all handler of {@link #around}, is written out by hand.
 */
final class MethodPatch {
    static final String HOOKS = "kotlinx/coroutree/runtime/Hooks";

    final String name;
    final String descriptor;
    /** Missing a required method means the library changed under us and the capture is wrong: reported loudly. */
    final boolean required;
    private final HookCall onEnter;
    private final HookCall onExit;
    private final boolean exitOnThrow;

    private MethodPatch(String name, String descriptor, boolean required, HookCall onEnter, HookCall onExit, boolean exitOnThrow) {
        this.name = name;
        this.descriptor = descriptor;
        this.required = required;
        this.onEnter = onEnter;
        this.onExit = onExit;
        this.exitOnThrow = exitOnThrow;
    }

    static MethodPatch enter(String name, String descriptor, HookCall hook) {
        return new MethodPatch(name, descriptor, true, hook, null, false);
    }

    /** Before every normal return. The only kind usable on constructors: by then {@code this} is initialized. */
    static MethodPatch exit(String name, String descriptor, HookCall hook) {
        return new MethodPatch(name, descriptor, true, null, hook, false);
    }

    static MethodPatch enterAndExit(String name, String descriptor, HookCall enter, HookCall exit) {
        return new MethodPatch(name, descriptor, true, enter, exit, false);
    }

    /** {@code enter} at the start ({@code null} for none), {@code exit} on the way out whether by return or by exception. */
    static MethodPatch around(String name, String descriptor, HookCall enter, HookCall exit) {
        return new MethodPatch(name, descriptor, true, enter, exit, true);
    }

    MethodPatch optional() {
        return new MethodPatch(name, descriptor, false, onEnter, onExit, exitOnThrow);
    }

    boolean matches(MethodNode method) {
        return method.name.equals(name) && method.desc.equals(descriptor);
    }

    /** Returns false if the method has no body to patch (abstract or native in this version of the class). */
    boolean apply(String owner, MethodNode method) {
        if (method.instructions.size() == 0) return false;
        Context context = new Context(owner, method);

        if (onExit != null) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                int opcode = insn.getOpcode();
                if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
                    method.instructions.insertBefore(insn, onExit.emit(context, opcode));
                }
            }
        }

        LabelNode start = new LabelNode();
        InsnList prologue = new InsnList();
        if (onEnter != null) prologue.add(onEnter.emit(context, -1));
        prologue.add(start);
        method.instructions.insert(prologue);

        if (exitOnThrow) {
            LabelNode end = new LabelNode();
            LabelNode handler = new LabelNode();
            InsnList epilogue = new InsnList();
            epilogue.add(end);
            epilogue.add(handler);
            // At the handler nothing is known about locals except `this`, and nothing but `this` is needed.
            Object[] locals = context.isStatic ? new Object[0] : new Object[] {owner};
            epilogue.add(new FrameNode(Opcodes.F_NEW, locals.length, locals, 1, new Object[] {"java/lang/Throwable"}));
            epilogue.add(onExit.emit(context, Opcodes.ATHROW));
            epilogue.add(new InsnNode(Opcodes.ATHROW));
            method.instructions.add(epilogue);
            // Last in the table: the method's own handlers keep priority over this catch-all.
            method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
        }
        return true;
    }

    /** What a {@link HookCall} needs to know about the method it is emitted into. */
    static final class Context {
        final String owner;
        final boolean isStatic;
        private final int[] argSlots;
        private final Type[] argTypes;

        Context(String owner, MethodNode method) {
            this.owner = owner;
            this.isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
            this.argTypes = Type.getArgumentTypes(method.desc);
            this.argSlots = new int[argTypes.length];
            int slot = isStatic ? 0 : 1;
            for (int i = 0; i < argTypes.length; i++) {
                argSlots[i] = slot;
                slot += argTypes[i].getSize();
            }
        }

        int argSlot(int index) {
            return argSlots[index];
        }

        Type argType(int index) {
            return argTypes[index];
        }
    }

    /** A call to one static method of Hooks, with the code that pushes its arguments. */
    static final class HookCall {
        private final String hook;
        private final String hookDescriptor;
        private final List<Arg> args = new ArrayList<>();

        HookCall(String hook, String hookDescriptor, Arg... args) {
            this.hook = hook;
            this.hookDescriptor = hookDescriptor;
            this.args.addAll(List.of(args));
        }

        /** {@code exitOpcode} is the return opcode the call precedes, ATHROW in the catch-all handler, -1 on entry. */
        InsnList emit(Context context, int exitOpcode) {
            InsnList code = new InsnList();
            for (Arg arg : args) arg.push(code, context, exitOpcode);
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, hook, hookDescriptor, false));
            return code;
        }
    }

    interface Arg {
        void push(InsnList code, Context context, int exitOpcode);
    }
}
