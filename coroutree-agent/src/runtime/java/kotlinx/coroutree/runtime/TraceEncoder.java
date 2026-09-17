package kotlinx.coroutree.runtime;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;

/**
 * Encodes frames of the trace format: a varint length followed by a {@code Frame} message.
 * Field numbers mirror coroutree-model's Trace.kt; WireCompatibilityTest decodes this encoder's output with the
 * model to keep the two in sync. Public only for that test. Used on the writer thread only.
 */
public final class TraceEncoder {
    private final OutputStream out;
    private final ProtoWriter frame = new ProtoWriter();
    private final ProtoWriter length = new ProtoWriter();
    private final HashMap<StackFrameRef, Integer> frameIds = new HashMap<>();

    public TraceEncoder(OutputStream out) {
        this.out = out;
    }

    public void writeMagic() throws IOException {
        out.write(Wire.MAGIC);
    }

    void writeHeader(AgentConfig config, long startedAtEpochMillis) throws IOException {
        frame.reset();
        ProtoWriter header = frame.beginMessage();
        header.int32(1, Wire.FORMAT_VERSION);
        header.string(2, BuildInfo.VERSION);
        header.string(3, config.buildId);
        header.string(4, config.taskPath);
        ProtoWriter jvm = header.beginMessage();
        jvm.int64(1, ProcessHandle.current().pid());
        jvm.string(2, System.getProperty("java.version"));
        jvm.string(3, System.getProperty("java.vm.name"));
        jvm.string(4, System.getProperty("java.vm.version"));
        jvm.string(5, System.getProperty("sun.java.command"));
        header.endMessage(5, jvm);
        ProtoWriter index = header.beginMessage();
        writeSourceIndex(index, config.sourceIndex);
        header.endMessage(6, index);
        for (String prefix : config.includePackages) header.string(7, prefix);
        for (String prefix : config.excludePackages) header.string(8, prefix);
        header.int64(9, startedAtEpochMillis);
        header.string(10, config.projectDir);
        frame.endMessage(1, header);
        flushFrame();
    }

    private static void writeSourceIndex(ProtoWriter index, List<SourceIndexFile.Module> modules) {
        for (SourceIndexFile.Module module : modules) {
            ProtoWriter m = index.beginMessage();
            m.string(1, module.path);
            m.string(2, module.rootDir);
            for (SourceIndexFile.Entry file : module.files) {
                ProtoWriter f = m.beginMessage();
                f.string(1, file.packageName);
                f.string(2, file.fileName);
                f.string(3, file.path);
                m.endMessage(3, f);
            }
            index.endMessage(1, m);
        }
    }

    void writeDiagnostic(TraceEvent.DiagnosticDef diagnostic) throws IOException {
        frame.reset();
        ProtoWriter message = frame.beginMessage();
        message.int32(1, diagnostic.severity);
        message.string(2, diagnostic.message);
        frame.endMessage(4, message);
        flushFrame();
    }

    void writeEvent(TraceEvent event) throws IOException {
        // Frame definitions go first: a reader must know a frame before it meets an event that refers to it.
        int[] stack = intern(event.stack);
        int[] exceptionStack = event.exception == null ? null : intern(event.exception.stack);
        int siteFrame = event.node == null || event.node.siteFrame == null ? 0 : intern(event.node.siteFrame);

        frame.reset();
        ProtoWriter e = frame.beginMessage();
        e.int64(1, event.seq);
        e.int64(2, event.timeNanos);
        e.int64(3, event.nodeId);
        e.int32(4, event.kind);
        e.int64(5, event.threadNodeId);
        e.packedInt32(6, stack);
        if (event.node != null) {
            ProtoWriter n = e.beginMessage();
            writeNode(n, event.node, siteFrame);
            e.endMessage(7, n);
        }
        e.int64(8, event.otherNodeId);
        if (event.exception != null) {
            TraceEvent.ExceptionDef exception = event.exception;
            ProtoWriter x = e.beginMessage();
            x.string(1, exception.className);
            x.string(2, exception.message);
            x.packedInt32(3, exceptionStack);
            x.int32(4, exception.identity);
            x.bool(5, exception.cancellation);
            e.endMessage(9, x);
        }
        if (event.contextDiff != null) {
            for (TraceEvent.ContextChangeDef change : event.contextDiff) {
                ProtoWriter c = e.beginMessage();
                c.int32(1, change.kind);
                c.string(2, change.key);
                c.string(3, change.oldValue);
                c.string(4, change.newValue);
                c.bool(5, change.added);
                c.bool(6, change.removed);
                e.endMessage(10, c);
            }
        }
        e.int32(11, event.blockReason);
        e.int32(12, event.handledBy);
        e.int32(13, event.direction);
        e.int32(14, event.finalState);
        frame.endMessage(2, e);
        flushFrame();
    }

    private static void writeNode(ProtoWriter n, TraceEvent.NodeDef node, int siteFrame) {
        n.int64(1, node.id);
        n.int32(2, node.kind);
        n.string(3, node.construct);
        n.string(4, node.name);
        n.int64(5, node.parentId);
        n.int64(6, node.creatorId);
        n.int32(7, siteFrame);
        n.int32(8, node.origin);
        if (node.context != null) {
            for (ContextEntry entry : node.context) {
                ProtoWriter c = n.beginMessage();
                c.int32(1, entry.kind);
                c.string(2, entry.key);
                c.string(3, entry.value);
                c.bool(4, entry.threadContextElement);
                n.endMessage(9, c);
            }
        }
        n.string(10, node.implClass);
        if (node.isThread) {
            ProtoWriter t = n.beginMessage();
            t.int64(1, node.tid);
            t.bool(2, node.virtual);
            t.bool(3, node.daemon);
            n.endMessage(11, t);
        }
    }

    private int[] intern(StackFrameRef[] stack) throws IOException {
        if (stack == null || stack.length == 0) return null;
        int[] ids = new int[stack.length];
        for (int i = 0; i < stack.length; i++) ids[i] = intern(stack[i]);
        return ids;
    }

    private int intern(StackFrameRef ref) throws IOException {
        Integer known = frameIds.get(ref);
        if (known != null) return known;
        int id = frameIds.size() + 1;
        frameIds.put(ref, id);
        frame.reset();
        ProtoWriter def = frame.beginMessage();
        def.int32(1, id);
        def.string(2, ref.className);
        def.string(3, ref.methodName);
        def.string(4, ref.fileName);
        def.int32(5, ref.line);
        frame.endMessage(3, def);
        flushFrame();
        return id;
    }

    private void flushFrame() throws IOException {
        length.reset();
        length.varint(frame.size());
        out.write(length.array(), 0, length.size());
        out.write(frame.array(), 0, frame.size());
    }
}
