package evolvia.launcher;

import evolvia.core.Platform;
import evolvia.launcher.TestArchives.Entry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LauncherCoreTest {

    /** Trimmed real-world shape of GitHub's GET /repos/{owner}/{repo}/releases/latest. */
    private static final String GITHUB_JSON = """
            {
              "url": "https://api.github.com/repos/Reity-byte/evolvia/releases/1",
              "tag_name": "v0.2",
              "name": "Evolvia 0.2",
              "draft": false,
              "prerelease": false,
              "assets": [
                {"name": "Evolvia-windows.zip", "size": 50000000, "browser_download_url": "https://github.com/Reity-byte/evolvia/releases/download/v0.2/Evolvia-windows.zip"},
                {"name": "EvolviaLauncher-windows.zip", "size": 40000000, "browser_download_url": "https://github.com/Reity-byte/evolvia/releases/download/v0.2/EvolviaLauncher-windows.zip"},
                {"name": "Evolvia-macos.zip", "size": 52000000, "browser_download_url": "https://github.com/Reity-byte/evolvia/releases/download/v0.2/Evolvia-macos.zip"},
                {"name": "EvolviaLauncher-macos.zip", "size": 41000000, "browser_download_url": "https://github.com/Reity-byte/evolvia/releases/download/v0.2/EvolviaLauncher-macos.zip"},
                {"name": "Evolvia-linux.tar.gz", "size": 51000000, "browser_download_url": "https://github.com/Reity-byte/evolvia/releases/download/v0.2/Evolvia-linux.tar.gz"},
                {"name": "EvolviaLauncher-linux.tar.gz", "size": 40500000, "browser_download_url": "https://github.com/Reity-byte/evolvia/releases/download/v0.2/EvolviaLauncher-linux.tar.gz"}
              ]
            }
            """;

    @TempDir
    Path temp;

    private LauncherCore core(Platform platform) {
        return new LauncherCore(temp.resolve("data"), platform, LauncherCore.localReleaseSource(temp.resolve("release")));
    }

    @Test
    void parsesGitHubRelease() throws IOException {
        Release release = LauncherCore.parseRelease(GITHUB_JSON);
        assertEquals("v0.2", release.tag());
        assertEquals(6, release.assets().size());
        assertEquals(50000000, release.assets().get(0).size());
        assertEquals(URI.create("https://github.com/Reity-byte/evolvia/releases/download/v0.2/Evolvia-windows.zip"),
                release.assets().get(0).url());
    }

    @Test
    void toleratesByteOrderMark() throws IOException {
        assertEquals("v1", LauncherCore.parseRelease("﻿{\"tag_name\": \"v1\", \"assets\": []}").tag());
    }

    @Test
    void rejectsReleaseWithoutTag() {
        assertThrows(IOException.class, () -> LauncherCore.parseRelease("{\"assets\": []}"));
        assertThrows(IOException.class, () -> LauncherCore.parseRelease("not json"));
    }

    @Test
    void picksGamePackageForEachPlatform() throws IOException {
        Release release = LauncherCore.parseRelease(GITHUB_JSON);
        assertEquals("Evolvia-windows.zip", LauncherCore.assetFor(release, Platform.WINDOWS).orElseThrow().name());
        assertEquals("Evolvia-macos.zip", LauncherCore.assetFor(release, Platform.MACOS).orElseThrow().name());
        assertEquals("Evolvia-linux.tar.gz", LauncherCore.assetFor(release, Platform.LINUX).orElseThrow().name());
    }

    @Test
    void ignoresLauncherWrongPrefixAndWrongExtension() {
        Release release = new Release("v1", List.of(
                new Release.Asset("EvolviaLauncher-windows.zip", URI.create("https://x/1"), 1),
                new Release.Asset("evolvia-launcher-windows.zip", URI.create("https://x/2"), 1),
                new Release.Asset("MinecraftClaude-windows.zip", URI.create("https://x/3"), 1),
                new Release.Asset("Evolvia-windows.tar.gz", URI.create("https://x/4"), 1)));
        assertTrue(LauncherCore.assetFor(release, Platform.WINDOWS).isEmpty());
    }

    @Test
    void comparesVersionsNumerically() {
        assertTrue(LauncherCore.compareVersions("v1.10", "v1.9") > 0);
        assertTrue(LauncherCore.compareVersions("v1.9", "v1.10") < 0);
        assertEquals(0, LauncherCore.compareVersions("v1.2", "1.2.0"));
        assertTrue(LauncherCore.compareVersions("v2", "v1.99.99") > 0);
        assertTrue(LauncherCore.compareVersions("v0.1.0-local.20260927183000", "v0.1.0") > 0);
        assertTrue(LauncherCore.compareVersions("v0.1.0-local.20260927183000", "v0.1.0-local.20260927173000") > 0);
    }

    @Test
    void newerOnlyWhenTagIsHigherOrNothingInstalled() {
        Release release = new Release("v1.2", List.of());
        assertTrue(LauncherCore.isNewer(release, null));
        assertTrue(LauncherCore.isNewer(release, "v1.1"));
        assertFalse(LauncherCore.isNewer(release, "v1.2"));
        assertFalse(LauncherCore.isNewer(release, "v1.3"));
    }

    @Test
    void installDirKeepsOnlySafeCharacters() {
        LauncherCore core = core(Platform.WINDOWS);
        Path versions = core.versionsDir();
        assertEquals(versions.resolve("v1.2"), core.installDir("v1.2"));
        assertEquals(versions, core.installDir("../../evil").getParent());
        assertEquals(versions, core.installDir("..").getParent());
        assertEquals(versions, core.installDir("v1/../../x").getParent());
        assertEquals(versions, core.installDir("C:\\Windows").getParent());
    }

    @Test
    void executablePathsMatchJpackageLayout() {
        Path windows = core(Platform.WINDOWS).executable("v1");
        Path mac = core(Platform.MACOS).executable("v1");
        Path linux = core(Platform.LINUX).executable("v1");
        assertTrue(windows.endsWith(Path.of("v1", "Evolvia", "Evolvia.exe")));
        assertTrue(mac.endsWith(Path.of("v1", "Evolvia.app", "Contents", "MacOS", "Evolvia")));
        assertTrue(linux.endsWith(Path.of("v1", "Evolvia", "bin", "Evolvia")));
    }

    @Test
    void nothingInstalledWithoutCurrentFileOrExecutable() throws IOException {
        LauncherCore core = core(Platform.WINDOWS);
        assertNull(core.installedVersion());
        Files.createDirectories(core.versionsDir());
        Files.writeString(core.versionsDir().resolve("current.txt"), "v1.0");
        assertNull(core.installedVersion(), "current.txt without the executable is not an installation");
    }

    @Test
    void installsPackageAndRecordsVersion() throws IOException {
        LauncherCore core = core(Platform.WINDOWS);
        Path zip = TestArchives.zip(temp.resolve("Evolvia-windows.zip"), List.of(
                Entry.file("Evolvia\\Evolvia.exe", "exe"),
                Entry.file("Evolvia\\app\\evolvia.jar", "jar")), false);
        core.install(zip, "v0.3");
        assertEquals("v0.3", core.installedVersion());
        assertTrue(Files.isRegularFile(core.executable("v0.3")));
    }

    @Test
    void failedInstallKeepsPreviousVersion() throws IOException {
        LauncherCore core = core(Platform.WINDOWS);
        Path good = TestArchives.zip(temp.resolve("good.zip"), List.of(Entry.file("Evolvia/Evolvia.exe", "exe")), false);
        core.install(good, "v1.0");

        Path noExecutable = TestArchives.zip(temp.resolve("bad.zip"), List.of(Entry.file("Evolvia/readme.txt", "x")), false);
        IOException e = assertThrows(IOException.class, () -> core.install(noExecutable, "v1.1"));
        assertTrue(e.getMessage().contains("does not contain the game executable"), e.getMessage());
        assertFalse(Files.exists(core.installDir("v1.1")));

        Path slip = TestArchives.zip(temp.resolve("slip.zip"), List.of(
                Entry.file("Evolvia/Evolvia.exe", "exe"), Entry.file("../../evil", "x")), false);
        assertThrows(IOException.class, () -> core.install(slip, "v1.2"));
        assertFalse(Files.exists(core.installDir("v1.2")));

        assertEquals("v1.0", core.installedVersion());
    }

    @Test
    void reinstallReplacesVersionFolder() throws IOException {
        LauncherCore core = core(Platform.WINDOWS);
        core.install(TestArchives.zip(temp.resolve("a.zip"), List.of(
                Entry.file("Evolvia/Evolvia.exe", "exe"), Entry.file("Evolvia/old.txt", "old")), false), "v1");
        core.install(TestArchives.zip(temp.resolve("b.zip"), List.of(
                Entry.file("Evolvia/Evolvia.exe", "exe2")), false), "v1");
        assertFalse(Files.exists(core.installDir("v1").resolve("Evolvia/old.txt")));
    }

    @Test
    void readsLocalReleaseAndDownloadsFileAssets() throws IOException, InterruptedException {
        Path releaseDir = Files.createDirectories(temp.resolve("release"));
        Path zip = TestArchives.zip(releaseDir.resolve("Evolvia-windows.zip"),
                List.of(Entry.file("Evolvia/Evolvia.exe", "exe")), false);
        String json = "{\"tag_name\": \"v0.1.0-local.1\", \"assets\": [{\"name\": \"Evolvia-windows.zip\", "
                + "\"size\": " + Files.size(zip) + ", \"browser_download_url\": \"" + zip.toUri() + "\"}]}";
        Files.writeString(releaseDir.resolve(LauncherCore.LOCAL_RELEASE_FILE), json, StandardCharsets.UTF_8);

        LauncherCore core = core(Platform.WINDOWS);
        Release release = core.latest();
        assertEquals("v0.1.0-local.1", release.tag());

        List<long[]> updates = new ArrayList<>();
        Path downloaded = core.download(LauncherCore.assetFor(release, Platform.WINDOWS).orElseThrow(),
                (done, total) -> updates.add(new long[]{done, total}));
        assertEquals(Files.size(zip), Files.size(downloaded));
        long[] last = updates.get(updates.size() - 1);
        assertEquals(last[1], last[0], "progress ends at 100 %");

        core.install(downloaded, release.tag());
        assertEquals("v0.1.0-local.1", core.installedVersion());
    }
}
