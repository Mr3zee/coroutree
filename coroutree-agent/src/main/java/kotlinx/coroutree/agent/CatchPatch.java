package kotlinx.coroutree.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Reports exceptions caught by project code: a call to {@code Hooks.exceptionCaught} at the start of every catch block
 * that handles something.
 *
 * Two kinds of handlers do not. Handlers of type "any" are how compilers implement {@code finally} and
 * {@code synchronized}: they rethrow. And some typed handlers are cleanup in disguise — Kotlin's {@code use} and
 * Java's try-with-resources catch {@code Throwable} only to close the resource and throw the same exception on;
 * reporting those would show every exception as "caught" once per resource it passes on its way up.
 */
final class CatchPatch {
    private CatchPatch() {}

    /** How far a handler is followed before giving up on proving that it only rethrows. */
    private static final int MAX_STEPS = 400;

    /** Returns whether anything was changed. */
    static boolean apply(ClassNode node) {
        boolean changed = false;
        for (MethodNode method : node.methods) {
            Set<LabelNode> done = new HashSet<>();
            for (TryCatchBlockNode block : method.tryCatchBlocks) {
                if (block.type == null || !done.add(block.handler)) continue;
                // The handler starts with the exception on the stack. Insert before its first real instruction: the
                // stack map frame (and any further labels and line numbers) at the handler's offset must stay in front.
                AbstractInsnNode first = firstReal(block.handler);
                if (first == null || alwaysRethrows(first)) continue;
                InsnList report = new InsnList();
                report.add(new InsnNode(Opcodes.DUP));
                report.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MethodPatch.HOOKS, "exceptionCaught", "(Ljava/lang/Throwable;)V", false));
                method.instructions.insertBefore(first, report);
                changed = true;
            }
        }
        return changed;
    }

    private static AbstractInsnNode firstReal(AbstractInsnNode from) {
        AbstractInsnNode insn = from;
        while (insn != null && insn.getOpcode() < 0) insn = insn.getNext();
        return insn;
    }

    /**
     * Whether every way out of the handler is {@code throw e} of the very exception it caught: the handler stores the
     * exception in a local, and every path from there ends in loading that local and throwing it. Paths that leave
     * through an exception of their own (a failing {@code close()}) are not followed; they do not handle the original
     * one either. When in doubt — a return, a switch, a handler too long to follow — the answer is no, and it is reported.
     */
    private static boolean alwaysRethrows(AbstractInsnNode handlerStart) {
        if (handlerStart.getOpcode() != Opcodes.ASTORE) return false;
        int caught = ((VarInsnNode) handlerStart).var;

        ArrayDeque<AbstractInsnNode> pending = new ArrayDeque<>();
        Set<AbstractInsnNode> seen = new HashSet<>();
        pending.add(handlerStart.getNext());
        int steps = 0;
        while (!pending.isEmpty()) {
            AbstractInsnNode insn = firstReal(pending.poll());
            while (true) {
                if (insn == null || ++steps > MAX_STEPS) return false;
                if (!seen.add(insn)) break; // joined a path that is already being checked
                int opcode = insn.getOpcode();
                if (opcode == Opcodes.ATHROW) {
                    AbstractInsnNode loaded = previousReal(insn);
                    if (loaded == null || loaded.getOpcode() != Opcodes.ALOAD || ((VarInsnNode) loaded).var != caught) return false;
                    break;
                }
                if (opcode == Opcodes.ASTORE && ((VarInsnNode) insn).var == caught) return false; // the local is something else now
                if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) return false;
                if (opcode == Opcodes.TABLESWITCH || opcode == Opcodes.LOOKUPSWITCH || opcode == Opcodes.JSR || opcode == Opcodes.RET) return false;
                if (insn instanceof JumpInsnNode jump) {
                    pending.add(jump.label);
                    if (opcode == Opcodes.GOTO) break;
                }
                insn = firstReal(insn.getNext());
            }
        }
        return true;
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode from) {
        AbstractInsnNode insn = from.getPrevious();
        while (insn != null && insn.getOpcode() < 0) insn = insn.getPrevious();
        return insn;
    }
}
