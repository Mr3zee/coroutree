package kotlinx.coroutree.runtime;

/** A stack frame by value. The writer thread interns these into frame ids. */
final class StackFrameRef {
    final String className;
    final String methodName;
    final String fileName;
    final int line;
    private int hash;

    StackFrameRef(String className, String methodName, String fileName, int line) {
        this.className = className;
        this.methodName = methodName;
        this.fileName = fileName == null ? "" : fileName;
        this.line = Math.max(line, 0); // negative means native or unknown
    }

    static StackFrameRef of(StackTraceElement element) {
        return new StackFrameRef(element.getClassName(), element.getMethodName(), element.getFileName(), element.getLineNumber());
    }

    static StackFrameRef[] of(StackTraceElement[] elements, int limit) {
        StackFrameRef[] result = new StackFrameRef[Math.min(elements.length, limit)];
        for (int i = 0; i < result.length; i++) result[i] = of(elements[i]);
        return result;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StackFrameRef that
            && line == that.line
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
