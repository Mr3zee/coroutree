package kotlinx.coroutree.agent;

import kotlinx.coroutree.runtime.MonitorProbe;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Which build of the native monitor probe a JVM gets. Platform directories and file names are a contract with the
 * module's build script, which packs the binaries, and with the Gradle plugin, which unpacks the right one.
 */
class MonitorProbeLoaderTest {
    @Test
    void aJvmThatWasStartedWithoutTheProbeSaysSoInsteadOfFailing() {
        // No -agentpath here: the native method is not there, which is an answer, not an error.
        assertFalse(MonitorProbe.connect());
    }

    @Test
    void theProbeOfAPlatformIsTheOneTheBuildPacksForItAndNoneWhereNobodyBuildsOne() {
        String[][] platforms = {
            // os.name, os.arch, <platform>/<file name>
            {"Mac OS X", "aarch64", "macos/libcoroutree-monitor.dylib"},
            {"Mac OS X", "x86_64", "macos/libcoroutree-monitor.dylib"}, // one universal binary
            {"Darwin", "arm64", "macos/libcoroutree-monitor.dylib"},
            {"Linux", "amd64", "linux-x64/libcoroutree-monitor.so"},
            {"Linux", "x86_64", "linux-x64/libcoroutree-monitor.so"},
            {"Linux", "aarch64", "linux-arm64/libcoroutree-monitor.so"},
            {"LINUX", "AMD64", "linux-x64/libcoroutree-monitor.so"},
            {"Windows 11", "amd64", "windows-x64/coroutree-monitor.dll"},
            {"Windows Server 2022", "amd64", "windows-x64/coroutree-monitor.dll"},
            {"Windows 11", "aarch64", null},
            {"Linux", "riscv64", null},
            {"Linux", "x86", null},
            {"Mac OS X", "ppc", null},
            {"FreeBSD", "amd64", null},
            {"", "", null},
        };
        String os = System.getProperty("os.name"), arch = System.getProperty("os.arch");
        try {
            for (String[] platform : platforms) {
                System.setProperty("os.name", platform[0]);
                System.setProperty("os.arch", platform[1]);
                assertEquals(platform[2], MonitorProbeLoader.hostLibrary(), platform[0] + " on " + platform[1]);
            }
        } finally {
            System.setProperty("os.name", os);
            System.setProperty("os.arch", arch);
        }
    }
}
