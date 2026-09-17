package kotlinx.coroutree.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

/** Everything that is done to one class. */
final class ClassPatch {
    private static final String TAGGED = "kotlinx/coroutree/runtime/Tagged";
    private static final String TAG_FIELD = "coroutree$tag";

    final String className;
    private final List<MethodPatch> methods = new ArrayList<>();
    private boolean tagged;

    ClassPatch(String className) {
        this.className = className;
    }

    ClassPatch method(MethodPatch patch) {
        methods.add(patch);
        return this;
    }

    /**
     * Makes the class implement {@code Tagged}: one object field with accessors. Changes the shape of the class, so
     * it only works on a class that is being loaded, not on a retransformed one.
     */
    ClassPatch tagged() {
        tagged = true;
        return this;
    }

    boolean changesShape() {
        return tagged;
    }

    /** Applies the patch. Returns the required methods that could not be patched; empty means all is well. */
    List<String> apply(ClassNode node) {
        List<String> missing = new ArrayList<>();
        for (MethodPatch patch : methods) {
            boolean applied = false;
            for (MethodNode method : node.methods) {
                if (patch.matches(method)) {
                    applied = patch.apply(node.name, method);
                    break;
                }
            }
            if (!applied && patch.required) missing.add(patch.name + patch.descriptor);
        }
        if (tagged) addTag(node);
        return missing;
    }

    private static void addTag(ClassNode node) {
        if (node.interfaces.contains(TAGGED)) return;
        node.interfaces.add(TAGGED);
        node.fields.add(new FieldNode(
            Opcodes.ACC_PRIVATE | Opcodes.ACC_VOLATILE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC,
            TAG_FIELD, "Ljava/lang/Object;", null, null));

        MethodNode getter = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, TAG_FIELD, "()Ljava/lang/Object;", null, null);
        getter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getter.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, TAG_FIELD, "Ljava/lang/Object;"));
        getter.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(getter);

        MethodNode setter = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, TAG_FIELD, "(Ljava/lang/Object;)V", null, null);
        setter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        setter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        setter.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, TAG_FIELD, "Ljava/lang/Object;"));
        setter.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(setter);
    }
}
