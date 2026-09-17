package kotlinx.coroutree.runtime;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The source index as the Gradle plugin writes it: UTF-8 text, one tab-separated record per line.
 * <pre>
 * M  &lt;gradle project path&gt;  &lt;absolute project dir&gt;          starts a module
 * F  &lt;package, may be empty&gt;  &lt;file name&gt;  &lt;path relative to the project dir&gt;   a file of the last module
 * </pre>
 * Indexes of several modules are merged by concatenating the files. The agent only re-encodes it into the header.
 */
public final class SourceIndexFile {
    private SourceIndexFile() {}

    public static final class Module {
        final String path;
        final String rootDir;
        final List<Entry> files = new ArrayList<>();

        Module(String path, String rootDir) {
            this.path = path;
            this.rootDir = rootDir;
        }
    }

    static final class Entry {
        final String packageName;
        final String fileName;
        final String path;

        Entry(String packageName, String fileName, String path) {
            this.packageName = packageName;
            this.fileName = fileName;
            this.path = path;
        }
    }

    static List<Module> read(File file) throws IOException {
        List<Module> modules = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            Module current = null;
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                String[] fields = line.split("\t", -1);
                if (fields[0].equals("M") && fields.length == 3) {
                    current = new Module(fields[1], fields[2]);
                    modules.add(current);
                } else if (fields[0].equals("F") && fields.length == 4 && current != null) {
                    current.files.add(new Entry(fields[1], fields[2], fields[3]));
                }
                // Anything else is a record of a newer plugin; skip it.
            }
        }
        return modules;
    }
}
