package kotlinx.coroutree.runtime;

import java.util.ArrayList;
import java.util.List;

/** What tests outside this package may ask of the runtime's package-private parts. */
public final class RuntimeProbe {
    private RuntimeProbe() {}

    /** {@link StackCapture#capture} from where it is called, as {@code Class.method}. */
    public static List<String> capture(int limit) {
        List<String> result = new ArrayList<>();
        for (StackFrameRef frame : StackCapture.capture(limit)) result.add(frame.className + "." + frame.methodName);
        return result;
    }

    /** The inline calls a line is in, innermost first, each as {@code function@line it is called from}. */
    public static String describe(SourceMaps.Inlined inlined) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < inlined.functions.length; i++) {
            if (i > 0) text.append(" < ");
            text.append(inlined.functions[i]).append('@').append(inlined.calledFrom[i]);
        }
        return text.toString();
    }

    /** The logical frames the runtime makes of one JVM frame, each as {@code [inline ]Class.method(File:line)}. */
    public static List<String> logicalFrames(String className, String methodName, String fileName, int line) {
        ArrayList<StackFrameRef> frames = new ArrayList<>();
        SourceMaps.add(frames, className, methodName, fileName, line);
        List<String> result = new ArrayList<>();
        for (StackFrameRef frame : frames) {
            result.add((frame.inlined ? "inline " : "") + frame.className + "." + frame.methodName + "(" + frame.fileName + ":" + frame.line + ")");
        }
        return result;
    }
}
