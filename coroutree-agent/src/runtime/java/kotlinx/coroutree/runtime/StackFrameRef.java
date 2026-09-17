package kotlinx.coroutree.runtime;

import java.util.ArrayList;

/** A stack frame by value. The writer thread interns these into frame ids. */
final class StackFrameRef {
    final String className;
    final String methodName;
    final String fileName;
    final int line;
    /**
     * The body of an inline function, compiled into the method of the next frame that is not inlined. The class is the
     * one that declares the function; the method is the function if the class file told, else empty. See {@link SourceMaps}.
     */
    final boolean inlined;
    private int hash;

    StackFrameRef(String className, String methodName, String fileName, int line) {
        this(className, methodName, fileName, line, false);
    }

    StackFrameRef(String className, String methodName, String fileName, int line, boolean inlined) {
        this.className = className;
        this.methodName = methodName;
        this.fileName = fileName == null ? "" : fileName;
        this.line = Math.max(line, 0); // negative means native or unknown
        this.inlined = inlined;
    }

    /** At most {@code limit} frames; a frame of the JVM that holds inlined code is more than one, see {@link SourceMaps}. */
    static StackFrameRef[] of(StackTraceElement[] elements, int limit) {
        ArrayList<StackFrameRef> result = new ArrayList<>(Math.min(elements.length, limit));
        for (int i = 0; i < elements.length && result.size() < limit; i++) add(result, elements[i]);
        return StackCapture.limit(result.toArray(new StackFrameRef[0]), limit);
    }

    static void add(ArrayList<StackFrameRef> out, StackTraceElement element) {
        SourceMaps.add(out, element.getClassName(), element.getMethodName(), element.getFileName(), element.getLineNumber());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StackFrameRef that
            && line == that.line
            && inlined == that.inlined
            && className.equals(that.className)
            && methodName.equals(that.methodName)
            && fileName.equals(that.fileName);
    }

    @Override
    public int hashCode() {
        int h = hash;
        if (h == 0) {
            h = 31 * (31 * className.hashCode() + methodName.hashCode()) + line;
            hash = h;
        }
        return h;
    }
}
