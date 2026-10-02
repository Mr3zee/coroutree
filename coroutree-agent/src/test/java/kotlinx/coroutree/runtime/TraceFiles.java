package kotlinx.coroutree.runtime;

import kotlinx.coroutree.model.Frame;
import kotlinx.coroutree.model.TraceReader;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/** What the agent wrote, read with the model: the other implementation of the trace format. */
final class TraceFiles {
    private TraceFiles() {}

    /** Every complete frame the file has now. A file that is still being written ends in the middle of a frame, which is its end. */
    static List<Frame> read(File file) {
        try (InputStream in = new FileInputStream(file)) {
            return read(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every frame up to the end of the stream. */
    static List<Frame> read(InputStream in) throws IOException {
        List<Frame> frames = new ArrayList<>();
        TraceReader reader = new TraceReader(in);
        for (Frame frame = reader.next(); frame != null; frame = reader.next()) frames.add(frame);
        return frames;
    }
}
