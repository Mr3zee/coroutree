package kotlinx.coroutree.gui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Stands in for the GUI, under the name of its main class: the same jar is the fake agent and, on the
 * {@code coroutreeGui} configuration, what {@code coroutreeView} launches. Each start appends its arguments,
 * one per line between {@code launched} and {@code end}, to {@code launched.txt} in the directory given by {@code --dir}.
 */
public final class MainKt {
    private MainKt() {
    }

    public static void main(String[] args) throws IOException {
        Path dir = null;
        for (int i = 0; i + 1 < args.length; i++) {
            if (args[i].equals("--dir")) dir = Path.of(args[i + 1]);
        }
        if (dir == null) throw new IllegalArgumentException("No --dir among " + String.join(" ", args));
        System.out.println("fake GUI is up in " + Path.of("").toAbsolutePath());
        Files.createDirectories(dir);
        List<String> lines = new ArrayList<>();
        lines.add("launched");
        lines.addAll(List.of(args));
        lines.add("end");
        // One write, so that a reader polling the file sees whole launches (short of a torn write, which "end" reveals).
        Files.writeString(
            dir.resolve("launched.txt"),
            String.join("\n", lines) + "\n",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        );
    }
}
