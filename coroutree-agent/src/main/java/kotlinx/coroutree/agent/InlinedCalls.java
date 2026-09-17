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
 * Which inline functions the code at a line was inlined through. A source map says from which file and line inlined
 * code came and where the outermost inline call is; it names no function and knows nothing of the calls in between.
 * The compiler says that for debuggers, with marker variables in the local variable table: {@code $i$f$name} spans
 * the inlined body of {@code name}, {@code $i$a$-name-…} spans an inlined lambda that was passed to {@code name}.
 *
 * The code at an instruction belongs to the innermost function body around it, except that a lambda belongs to
 * whoever passed it: walking outwards, a lambda of {@code f} cancels the next body of {@code f}. The bodies that are
 * left, innermost first, are the inline calls the code is in. Where each was called from is the line in force right
 * before its marker's span: the arguments of the call are evaluated under it, and so is the marker set.
 */
final class InlinedCalls {
    private InlinedCalls() {}

    private static final String FUNCTION = "$i$f$";
    private static final String LAMBDA = "$i$a$-";

    /** By line, for the lines that are in an inline function's body. Lines of inlined code are unique in a class, so one map will do. */
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
                    // A marker is set by the first instructions of what it marks, which are therefore outside of its
                    // span and look like the enclosing function's. The line's deepest instruction is the one to ask.
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

        // The line in force at every instruction, to ask for the one before a span.
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
        // A suspending method is a state machine: after every suspension point the variables of the spans that are
        // still open are set anew, under the method's first line, and the spans begin again. That is the same call.
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

    /** Spans nest, so the later one starts, the further in it is. Not a lambda: nothing here may bootstrap itself inside a transformer. */
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
        /** The line, in the numbering of the class file, that the inline call is on. */
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
