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
 * Hook code must have no branches. Recomputing stack map frames would load classes inside the transformer.
 * {@link #around} adds one frame at its catch-all handler without hierarchy lookup.
 */
final class MethodPatch {
    static final String HOOKS = "kotlinx/coroutree/runtime/Hooks";

    final String name;
    final String descriptor;
    /** A missing required method is an instrumentation error. */
    final boolean required;
    private final HookCall onEnter;
    private final HookCall onExit;
    private final boolean exitOnThrow;
    /** Set for {@link #beforeCall}: the call, as owner, name and descriptor, that {@code onEnter} goes in front of. */
    private final String[] call;

    private MethodPatch(String name, String descriptor, boolean required, HookCall onEnter, HookCall onExit, boolean exitOnThrow, String[] call) {
        this.name = name;
        this.descriptor = descriptor;
        this.required = required;
        this.onEnter = onEnter;
        this.onExit = onExit;
        this.exitOnThrow = exitOnThrow;
        this.call = call;
    }

    private MethodPatch(String name, String descriptor, boolean required, HookCall onEnter, HookCall onExit, boolean exitOnThrow) {
        this(name, descriptor, required, onEnter, onExit, exitOnThrow, null);
    }

    static MethodPatch enter(String name, String descriptor, HookCall hook) {
        return new MethodPatch(name, descriptor, true, hook, null, false);
    }

    /** The only patch usable on constructors because {@code this} is initialized at a normal return. */
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

    /**
     * The target call must have a receiver and one single-slot argument, as required by {@link Args#callOperands}.
     */
    static MethodPatch beforeCall(String name, String descriptor, String callOwner, String callName, String callDescriptor, HookCall hook) {
        return new MethodPatch(name, descriptor, true, hook, null, false, new String[] {callOwner, callName, callDescriptor});
    }

    MethodPatch optional() {
        return new MethodPatch(name, descriptor, false, onEnter, onExit, exitOnThrow, call);
    }

    boolean matches(MethodNode method) {
        return method.name.equals(name) && method.desc.equals(descriptor);
    }

    /** Returns false if the method has no body to patch (abstract or native in this version of the class). */
    boolean apply(String owner, MethodNode method) {
        if (method.instructions.size() == 0) return false;
        Context context = new Context(owner, method);
        if (call != null) return applyBeforeCalls(method, context);

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
            // Only `this` has a known type at this handler, and the exit hook needs no other locals.
            Object[] locals = context.isStatic ? new Object[0] : new Object[] {owner};
            epilogue.add(new FrameNode(Opcodes.F_NEW, locals.length, locals, 1, new Object[] {"java/lang/Throwable"}));
            epilogue.add(onExit.emit(context, Opcodes.ATHROW));
            epilogue.add(new InsnNode(Opcodes.ATHROW));
            method.instructions.add(epilogue);
            // The method's own handlers must take priority over this catch-all.
            method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
        }
        return true;
    }

    private boolean applyBeforeCalls(MethodNode method, Context context) {
        boolean found = false;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode made && made.owner.equals(call[0]) && made.name.equals(call[1]) && made.desc.equals(call[2])) {
                method.instructions.insertBefore(insn, onEnter.emit(context, -1));
                found = true;
            }
        }
        return found;
    }

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
