package kotlinx.coroutree.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kotlin inline copies use line numbers beyond the caller's source file. JSR-45 source maps (SMAP) identify their source:
 * <ul>
 * <li>{@code Kotlin} maps copied code to its original file and line.</li>
 * <li>{@code KotlinDebug} maps copied code to the caller's inline call site.</li>
 * </ul>
 *
 * With both strata, a JVM frame expands to the inline body, intermediate calls from {@link Inlined}, and the outer call site.
 * With only the first stratum, a frame gets its original file and line without expansion.
 *
 * Classes are keyed by name. If two loaders define the same name differently, the last map wins.
 */
public final class SourceMaps {
    private static final ConcurrentHashMap<String, SourceMaps> BY_CLASS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> SUSPEND_MAINS = new ConcurrentHashMap<>();

    /** Inline function names, innermost first. SMAP doesn't include these names. */
    public static final class Inlined {
        final String[] functions;
        /** Each call site's class-file line number, within the next enclosing function's inline copy. */
        final int[] calledFrom;

        public Inlined(String[] functions, int[] calledFrom) {
            this.functions = functions;
            this.calledFrom = calledFrom;
        }
    }

    /**
     * {@code smap} is the class's SourceDebugExtension. Optional {@code inlined} entries supplement it from local variable tables.
     * Never throws.
     */
    public static void register(String className, String smap, Map<Integer, Inlined> inlined) {
        try {
            SourceMaps maps = parse(className, smap, inlined);
            if (maps != null) BY_CLASS.put(className, maps);
        } catch (RuntimeException e) {
            Tracer.reportInternalError("cannot read the source map of " + className, e);
        }
    }

    /**
     * The generated main(String[]) has no line numbers. {@code line} locates the source suspend main declaration.
     */
    public static void registerSuspendMain(String className, int line) {
        if (line > 0) SUSPEND_MAINS.put(className, line);
    }

    static void add(ArrayList<StackFrameRef> out, String className, String methodName, String fileName, int line) {
        if (line <= 0 && !SUSPEND_MAINS.isEmpty() && methodName.equals("main")) {
            Integer declared = SUSPEND_MAINS.get(className);
            if (declared != null) line = declared;
        }
        SourceMaps maps = BY_CLASS.isEmpty() ? null : BY_CLASS.get(className);
        int body = maps == null ? -1 : maps.body.find(line);
        if (body < 0) {
            out.add(new StackFrameRef(className, methodName, fileName, line));
            return;
        }
        Stratum from = maps.body;
        int bodyLine = from.inStart[body] + (line - from.outStart[body]) / from.step[body];
        int site = maps.callSite == null ? -1 : maps.callSite.find(line);
        if (site < 0) {
            // An inline copy without call-site metadata needs source correction without frame expansion.
            out.add(new StackFrameRef(from.classOf(body), methodName, from.fileOf(body), bodyLine));
            return;
        }
        Inlined through = maps.inlined == null ? null : maps.inlined.get(line);
        out.add(new StackFrameRef(from.classOf(body), through == null ? "" : through.functions[0], from.fileOf(body), bodyLine, true));
        for (int i = 1; through != null && i < through.functions.length; i++) {
            // The previous function's call site is a line in this function's inline copy.
            int calledFrom = through.calledFrom[i - 1];
            int range = from.find(calledFrom);
            if (range < 0) break;
            int calledFromLine = from.inStart[range] + (calledFrom - from.outStart[range]) / from.step[range];
            out.add(new StackFrameRef(from.classOf(range), through.functions[i], from.fileOf(range), calledFromLine, true));
        }
        Stratum at = maps.callSite;
        // Every output line in a call-site range maps to one input line, including the body-range form "14#1,3:36".
        String siteClass = samePackage(at.classOf(site), className) ? className : at.classOf(site);
        out.add(new StackFrameRef(siteClass, methodName, at.fileOf(site), at.inStart[site]));
    }

    private final Stratum body;
    private final Stratum callSite;
    private final Map<Integer, Inlined> inlined;

    private SourceMaps(Stratum body, Stratum callSite, Map<Integer, Inlined> inlined) {
        this.body = body;
        this.callSite = callSite;
        this.inlined = inlined;
    }

    /** Lines of one stratum: ranges of output lines, sorted, each with the input file and line it starts at. */
    private static final class Stratum {
        final String[] fileNames;
        final String[] classNames;
        final int[] outStart;
        final int[] outEnd;
        final int[] inStart;
        final int[] step;
        final int[] file;

        Stratum(String[] fileNames, String[] classNames, ArrayList<int[]> ranges) {
            this.fileNames = fileNames;
            this.classNames = classNames;
            ranges.sort(new Comparator<int[]>() {
                @Override
                public int compare(int[] a, int[] b) {
                    return Integer.compare(a[0], b[0]);
                }
            });
            int n = ranges.size();
            outStart = new int[n];
            outEnd = new int[n];
            inStart = new int[n];
            step = new int[n];
            file = new int[n];
            for (int i = 0; i < n; i++) {
                int[] range = ranges.get(i);
                outStart[i] = range[0];
                outEnd[i] = range[1];
                inStart[i] = range[2];
                step[i] = range[3];
                file[i] = range[4];
            }
        }

        /** Index of the range that holds the output line, -1 if none does. */
        int find(int line) {
            int low = 0, high = outStart.length - 1;
            while (low <= high) {
                int mid = (low + high) >>> 1;
                if (outStart[mid] <= line) low = mid + 1;
                else high = mid - 1;
            }
            return high >= 0 && line <= outEnd[high] ? high : -1;
        }

        String fileOf(int range) {
            return fileNames[file[range]];
        }

        String classOf(int range) {
            return classNames[file[range]];
        }
    }

    private static final String BODY_STRATUM = "Kotlin";
    private static final String CALL_SITE_STRATUM = "KotlinDebug";

    private static SourceMaps parse(String className, String smap, Map<Integer, Inlined> inlined) {
        if (smap == null || !smap.startsWith("SMAP")) return null;
        String[] lines = smap.split("\r?\n");
        if (lines.length < 4) return null;
        String outputFile = lines[1];

        Stratum body = null, callSite = null;
        String stratum = null;
        char section = 0;
        ArrayList<String> fileNames = new ArrayList<>();
        ArrayList<String> classNames = new ArrayList<>();
        HashMap<Integer, Integer> fileIndex = new HashMap<>();
        ArrayList<int[]> ranges = new ArrayList<>();
        int lastFile = 0;

        for (int i = 3; i <= lines.length; i++) {
            String line = i < lines.length ? lines[i] : "*E";
            if (line.startsWith("*")) {
                boolean ends = line.startsWith("*S ") || line.equals("*E");
                if (ends && stratum != null) {
                    Stratum parsed = new Stratum(fileNames.toArray(new String[0]), classNames.toArray(new String[0]), ranges);
                    if (stratum.equals(BODY_STRATUM)) body = parsed;
                    else if (stratum.equals(CALL_SITE_STRATUM)) callSite = parsed;
                    stratum = null;
                }
                if (line.startsWith("*S ")) {
                    stratum = line.substring(3).trim();
                    fileNames = new ArrayList<>();
                    classNames = new ArrayList<>();
                    fileIndex = new HashMap<>();
                    ranges = new ArrayList<>();
                    lastFile = 0;
                }
                section = line.length() > 1 ? line.charAt(1) : 0;
                continue;
            }
            if (stratum == null) continue;
            if (section == 'F') {
                // Extended entries put a class path after "+ 2 Helpers.kt". Short entries use only "2 Helpers.kt".
                boolean withPath = line.startsWith("+ ");
                String entry = withPath ? line.substring(2) : line;
                int space = entry.indexOf(' ');
                if (space < 0) continue;
                int id = Integer.parseInt(entry.substring(0, space).trim());
                String path = withPath && i + 1 < lines.length ? lines[++i] : "";
                fileIndex.put(id, fileNames.size());
                fileNames.add(entry.substring(space + 1));
                classNames.add(path.replace('/', '.'));
            } else if (section == 'L') {
                // InputStartLine [# LineFileID] [, RepeatCount] : OutputStartLine [, OutputLineIncrement]
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String in = line.substring(0, colon), out = line.substring(colon + 1);
                int repeat = 1, increment = 1;
                int comma = in.indexOf(',');
                if (comma >= 0) {
                    repeat = Integer.parseInt(in.substring(comma + 1).trim());
                    in = in.substring(0, comma);
                }
                int hash = in.indexOf('#');
                if (hash >= 0) {
                    lastFile = Integer.parseInt(in.substring(hash + 1).trim());
                    in = in.substring(0, hash);
                }
                comma = out.indexOf(',');
                if (comma >= 0) {
                    increment = Integer.parseInt(out.substring(comma + 1).trim());
                    out = out.substring(0, comma);
                }
                Integer file = fileIndex.get(lastFile);
                if (file == null || repeat < 1 || increment < 1) continue;
                int inStart = Integer.parseInt(in.trim()), outStart = Integer.parseInt(out.trim());
                // Identity mappings need no source correction.
                boolean itself = stratum.equals(BODY_STRATUM) && inStart == outStart && increment == 1
                    && fileNames.get(file).equals(outputFile) && samePackage(classNames.get(file), className);
                if (!itself) ranges.add(new int[] {outStart, outStart + repeat * increment - 1, inStart, increment, file});
            }
        }
        if (body == null || body.outStart.length == 0) return null;

        Map<Integer, Inlined> ofMappedLines = null;
        if (inlined != null) {
            ofMappedLines = new HashMap<>();
            for (Map.Entry<Integer, Inlined> entry : inlined.entrySet()) {
                if (body.find(entry.getKey()) >= 0) ofMappedLines.put(entry.getKey(), entry.getValue());
            }
        }
        return new SourceMaps(body, callSite, ofMappedLines);
    }

    private static boolean samePackage(String className, String other) {
        int end = className.lastIndexOf('.');
        return end == other.lastIndexOf('.') && (end < 0 || className.regionMatches(0, other, 0, end));
    }
}
