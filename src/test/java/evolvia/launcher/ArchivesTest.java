package evolvia.launcher;

import evolvia.launcher.TestArchives.Entry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchivesTest {

    @TempDir
    Path temp;

    private Path target() {
        return temp.resolve("out");
    }

    private String read(String relative) throws IOException {
        return Files.readString(target().resolve(relative), StandardCharsets.UTF_8);
    }

    @Test
    void extractsZip() throws IOException {
        Path zip = TestArchives.zip(temp.resolve("a.zip"), List.of(
                Entry.dir("Evolvia/"),
                Entry.file("Evolvia/app/evolvia.jar", "jar"),
                Entry.file("Evolvia/Evolvia.exe", "exe")), false);
        Archives.extract(zip, target());
        assertEquals("jar", read("Evolvia/app/evolvia.jar"));
        assertEquals("exe", read("Evolvia/Evolvia.exe"));
    }

    @Test
    void convertsPowerShellBackslashes() throws IOException {
        // PowerShell 5.1 Compress-Archive: "dir\file" and folders as "dir\".
        Path zip = TestArchives.zip(temp.resolve("ps.zip"), List.of(
                Entry.file("Evolvia\\", ""),
                Entry.file("Evolvia\\runtime\\bin\\java.dll", "dll"),
                Entry.file("Evolvia\\Evolvia.exe", "exe")), false);
        Archives.extract(zip, target());
        assertTrue(Files.isDirectory(target().resolve("Evolvia")));
        assertEquals("dll", read("Evolvia/runtime/bin/java.dll"));
        assertEquals("exe", read("Evolvia/Evolvia.exe"));
    }

    @Test
    void rejectsZipSlip() throws IOException {
        for (String evil : new String[]{"../evil.txt", "..\\evil.txt", "Evolvia/../../evil.txt", "/evil.txt"}) {
            Path zip = TestArchives.zip(temp.resolve("slip.zip"), List.of(
                    Entry.file("Evolvia/ok.txt", "ok"),
                    Entry.file(evil, "evil")), false);
            IOException e = assertThrows(IOException.class, () -> Archives.extract(zip, target()), evil);
            assertTrue(e.getMessage().contains("rejected"), e.getMessage());
            assertFalse(Files.exists(temp.resolve("evil.txt")), evil);
        }
    }

    @Test
    void readsUnixModesFromZip() throws IOException {
        Path zip = TestArchives.zip(temp.resolve("mac.zip"), List.of(
                Entry.executable("Evolvia.app/Contents/MacOS/Evolvia", "bin"),
                Entry.file("Evolvia.app/Contents/Info.plist", "plist")), true);
        Map<String, Integer> modes = Archives.readZipUnixModes(zip);
        assertEquals(0100755, modes.get("Evolvia.app/Contents/MacOS/Evolvia"));
        assertEquals(0100644, modes.get("Evolvia.app/Contents/Info.plist"));
    }

    @Test
    void zipWithoutUnixModesHasNoModes() throws IOException {
        Path zip = TestArchives.zip(temp.resolve("win.zip"), List.of(Entry.file("a.txt", "a")), false);
        assertTrue(Archives.readZipUnixModes(zip).isEmpty());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void restoresExecutableBitAndSymlinksFromZip() throws IOException {
        Path zip = TestArchives.zip(temp.resolve("mac.zip"), List.of(
                Entry.executable("Evolvia.app/Contents/MacOS/Evolvia", "bin"),
                Entry.file("Evolvia.app/Contents/runtime/lib/libjli.dylib", "lib"),
                Entry.symlink("Evolvia.app/Contents/runtime/MacOS/libjli.dylib", "../lib/libjli.dylib")), true);
        Archives.extract(zip, target());
        assertTrue(Files.getPosixFilePermissions(target().resolve("Evolvia.app/Contents/MacOS/Evolvia"))
                .contains(PosixFilePermission.OWNER_EXECUTE));
        Path link = target().resolve("Evolvia.app/Contents/runtime/MacOS/libjli.dylib");
        assertTrue(Files.isSymbolicLink(link));
        assertEquals("lib", Files.readString(link));
    }

    @Test
    void rejectsZipSymlinkOutsideTarget() throws IOException {
        Path zip = TestArchives.zip(temp.resolve("link.zip"), List.of(
                Entry.symlink("Evolvia/escape", "../../outside")), true);
        IOException e = assertThrows(IOException.class, () -> Archives.extract(zip, target()));
        assertTrue(e.getMessage().contains("Symlink points outside"), e.getMessage());
    }

    @Test
    void extractsTarGzWithLongNames() throws IOException {
        String longName = "Evolvia/lib/runtime/legal/" + "x".repeat(90) + "/LICENSE";
        Path tgz = TestArchives.tarGz(temp.resolve("a.tar.gz"), List.of(
                Entry.dir("Evolvia/"),
                Entry.executable("Evolvia/bin/Evolvia", "launcher"),
                Entry.file(longName, "license")));
        Archives.extract(tgz, target());
        assertEquals("launcher", read("Evolvia/bin/Evolvia"));
        assertEquals("license", read(longName));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void restoresExecutableBitAndSymlinksFromTar() throws IOException {
        Path tgz = TestArchives.tarGz(temp.resolve("a.tar.gz"), List.of(
                Entry.executable("Evolvia/bin/Evolvia", "launcher"),
                Entry.file("Evolvia/lib/runtime/legal/java.base/LICENSE", "license"),
                Entry.symlink("Evolvia/lib/runtime/legal/java.sql/LICENSE", "../java.base/LICENSE")));
        Archives.extract(tgz, target());
        assertTrue(Files.isExecutable(target().resolve("Evolvia/bin/Evolvia")));
        assertEquals("license", read("Evolvia/lib/runtime/legal/java.sql/LICENSE"));
    }

    @Test
    void rejectsTarSlipAndOutsideSymlinks() throws IOException {
        Path slip = TestArchives.tarGz(temp.resolve("slip.tar.gz"), List.of(Entry.file("../evil.txt", "evil")));
        assertThrows(IOException.class, () -> Archives.extract(slip, target()));
        assertFalse(Files.exists(temp.resolve("evil.txt")));

        Path link = TestArchives.tarGz(temp.resolve("link.tar.gz"), List.of(Entry.symlink("Evolvia/x", "/etc/passwd")));
        assertThrows(IOException.class, () -> Archives.extract(link, temp.resolve("out2")));
    }

    @Test
    void rejectsUnknownArchiveType() {
        assertThrows(IOException.class, () -> Archives.extract(temp.resolve("x.rar"), target()));
    }
}
