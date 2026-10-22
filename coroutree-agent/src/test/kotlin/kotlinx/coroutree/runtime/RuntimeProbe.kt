package kotlinx.coroutree.runtime

/** The runtime's package-private parts, for tests outside this package. */
object RuntimeProbe {
    /** [StackCapture.capture] from the caller, each frame as `Class.method`. */
    @JvmStatic
    fun capture(limit: Int): List<String> = StackCapture.capture(limit).map { "${it.className}.${it.methodName}" }

    /** The inline calls a line is in, innermost first, each as `function@line it is called from`. */
    fun describe(inlined: SourceMaps.Inlined): String =
        inlined.functions.indices.joinToString(" < ") { "${inlined.functions[it]}@${inlined.calledFrom[it]}" }

    /** The logical frames the runtime makes of one JVM frame, each as `[inline ]Class.method(File:line)`. */
    fun logicalFrames(className: String, methodName: String, fileName: String?, line: Int): List<String> {
        val frames = ArrayList<StackFrameRef>()
        SourceMaps.add(frames, className, methodName, fileName, line)
        return frames.map { (if (it.inlined) "inline " else "") + "${it.className}.${it.methodName}(${it.fileName}:${it.line})" }
    }
}
