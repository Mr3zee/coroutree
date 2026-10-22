package kotlinx.coroutree.agent;

import kotlinx.coroutree.runtime.SourceMaps;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Source maps omit function names and intermediate inline calls. Compiler markers give this information:
 * <ul>
 * <li>{@code $i$f$name} spans the inline body of {@code name}.</li>
 * <li>{@code $i$a$-name-…} spans an inline lambda passed to {@code name}.</li>
 * </ul>
 * A lambda belongs to its caller, so its marker cancels the next enclosing body marker of the same function.
 * The remaining bodies form the inline stack, innermost first.
 * Each call site's line is the line active before its marker span, where arguments and the marker are evaluated.
 */
final class InlinedCalls {
    private InlinedCalls() {}

    private static final String FUNCTION = "$i$f$";
    private static final String LAMBDA = "$i$a$-";

    /** Inline line numbers are unique across all methods of a class, so one map covers the class. */
    static Map<Integer, SourceMaps.Inlined> of(ClassNode node) {
        Map<Integer, SourceMaps.Inlined> calls = new HashMap<>();
        Map<Integer, Integer> depths = new HashMap<>();
        for (MethodNode method : node.methods) {
            List<Scope> scopes = scopes(method);
            if (scopes.isEmpty()) continue;
            int line = 0, index = 0;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof LineNumberNode number) line = number.line;
                if (insn.getOpcode() >= 0 && line > 0) {
                    int depth = 0;
                    for (Scope scope : scopes) if (scope.contains(index)) depth++;
                    // Marker setup precedes its own span. Use the deepest instruction to identify the line's inline body.
                    Integer known = depths.get(line);
                    if (depth > 0 && (known == null || known < depth)) {
                        depths.put(line, depth);
                        List<Scope> around = new ArrayList<>(depth);
                        for (Scope scope : scopes) if (scope.contains(index)) around.add(scope);
                        around.sort(INNERMOST_FIRST);
                        SourceMaps.Inlined through = through(around);
                        if (through != null) calls.put(line, through);
                        else calls.remove(line);
                    }
                }
                index++;
            }
        }
        return calls;
    }

    private static SourceMaps.Inlined through(List<Scope> innermostFirst) {
        List<String> passedTo = new ArrayList<>();
        List<Scope> bodies = new ArrayList<>();
        for (Scope scope : innermostFirst) {
            if (scope.lambda) {
                passedTo.add(scope.function);
            } else if (!passedTo.remove(scope.function)) {
                bodies.add(scope);
            }
        }
        if (bodies.isEmpty()) return null;
        String[] functions = new String[bodies.size()];
        int[] calledFrom = new int[bodies.size()];
        for (int i = 0; i < functions.length; i++) {
            functions[i] = bodies.get(i).function;
            calledFrom[i] = bodies.get(i).calledFrom;
        }
        return new SourceMaps.Inlined(functions, calledFrom);
    }

    private static List<Scope> scopes(MethodNode method) {
        List<Scope> scopes = new ArrayList<>();
        if (method.localVariables == null) return scopes;
        boolean anyFunction = false;
        for (LocalVariableNode variable : method.localVariables) {
            boolean function = variable.name.startsWith(FUNCTION), lambda = variable.name.startsWith(LAMBDA);
            if (!function && !lambda) continue;
            anyFunction |= function;
            String name = variable.name.substring((function ? FUNCTION : LAMBDA).length());
            if (lambda) {
                int end = name.indexOf('-');
                if (end > 0) name = name.substring(0, end);
            }
            scopes.add(new Scope(name, lambda, variable.index, method.instructions.indexOf(variable.start), method.instructions.indexOf(variable.end)));
        }
        if (!anyFunction) {
            scopes.clear();
            return scopes;
        }

        int[] lineBefore = new int[method.instructions.size() + 1];
        int line = 0, firstLine = 0, index = 0, lineOfLastReal = 0;
        for (AbstractInsnNode insn : method.instructions) {
            lineBefore[index++] = lineOfLastReal;
            if (insn instanceof LineNumberNode number) {
                line = number.line;
                if (firstLine == 0) firstLine = line;
            }
            if (insn.getOpcode() >= 0) lineOfLastReal = line;
        }
        // After suspension, marker spans restart at the method's first line. Reuse the original call site for those spans.
        scopes.sort(INNERMOST_FIRST.reversed());
        Map<String, Integer> lastCall = new HashMap<>();
        for (Scope scope : scopes) {
            String key = scope.slot + (scope.lambda ? LAMBDA : FUNCTION) + scope.function;
            int calledFrom = lineBefore[scope.start];
            Integer before = lastCall.get(key);
            if (calledFrom == firstLine && before != null) calledFrom = before;
            scope.calledFrom = calledFrom;
            lastCall.put(key, calledFrom);
        }
        return scopes;
    }

    /** Avoid lambda bootstrap during transformation. */
    private static final Comparator<Scope> INNERMOST_FIRST = new Comparator<>() {
        @Override
        public int compare(Scope a, Scope b) {
            return Integer.compare(b.start, a.start);
        }
    };

    private static final class Scope {
        final String function;
        final boolean lambda;
        final int slot;
        final int start;
        final int end;
        /** Call-site line in class-file numbering. */
        int calledFrom;

        Scope(String function, boolean lambda, int slot, int start, int end) {
            this.function = function;
            this.lambda = lambda;
            this.slot = slot;
            this.start = start;
            this.end = end;
        }

        boolean contains(int index) {
            return start <= index && index < end;
        }
    }
}
