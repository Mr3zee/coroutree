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
 * Excludes cleanup handlers from exception-caught reports:
 * <ul>
 * <li>Untyped handlers implement {@code finally} or {@code synchronized} and rethrow.</li>
 * <li>Typed handlers in Kotlin's {@code use} and Java's try-with-resources can also rethrow the original exception.</li>
 * </ul>
 */
final class CatchPatch {
    private CatchPatch() {}

    /** Maximum instructions examined to prove that a handler only rethrows. */
    private static final int MAX_STEPS = 400;

    static boolean apply(ClassNode node) {
        boolean changed = false;
        for (MethodNode method : node.methods) {
            Set<LabelNode> done = new HashSet<>();
            for (TryCatchBlockNode block : method.tryCatchBlocks) {
                if (block.type == null || !done.add(block.handler)) continue;
                // Handler frames, labels, and line numbers must precede the inserted hook.
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
     * Checks explicit paths for rethrows of the caught exception. Exceptions from cleanup calls aren't followed.
     * A return, switch, or scan limit prevents proof, so the handler is reported as a catch.
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
