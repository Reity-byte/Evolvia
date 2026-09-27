package evolvia.launcher;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;
import evolvia.core.GameDirs;
import evolvia.core.Platform;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Launcher logic without any UI. Only {@link #latest()} and {@link #download} need the network;
 * everything else works offline and is unit tested.
 * <p>
 * Layout of the data folder: {@code versions/<tag>/} holds one unpacked game package
 * (a jpackage app image), {@code versions/current.txt} names the installed tag.
 */
public final class LauncherCore {

    /** GitHub repository that publishes the releases (must be public: the API is used without a token). */
    public static final String REPO = "Reity-byte/Evolvia";
    public static final URI GITHUB_LATEST_RELEASE = URI.create("https://api.github.com/repos/" + REPO + "/releases/latest");
    /** Game packages are named {@code Evolvia-<os>.<ext>}; compared case-insensitively. */
    static final String PACKAGE_PREFIX = "evolvia-";
    /** Name of the game app image, must match {@code jpackage --name} in release.yml / package.ps1. */
    static final String APP_NAME = "Evolvia";
    /** File name of the release description inside a local release folder. */
    public static final String LOCAL_RELEASE_FILE = "release.json";
    static final String CURRENT_FILE = "current.txt";
    /** How long the launcher waits after starting the game before it closes. */
    public static final Duration LAUNCH_GRACE = Duration.ofMillis(1500);

    private static final Gson GSON = new Gson();
    private static final Pattern NUMBER = Pattern.compile("\\d+");

    /** Download progress callback. {@code total} is -1 when unknown. */
    @FunctionalInterface
    public interface Progress {
        void update(long done, long total);
    }

    private final Path dataRoot;
    private final Platform platform;
    private final URI releaseSource;
    /** Created on first use, so a restricted network cannot break the offline parts. */
    private HttpClient http;

    /**
     * @param dataRoot      data folder ({@link GameDirs#root()})
     * @param platform      platform whose packages are installed
     * @param releaseSource GitHub "latest release" API URL, or a {@code file:} URI of a local release.json
     */
    public LauncherCore(Path dataRoot, Platform platform, URI releaseSource) {
        this.dataRoot = dataRoot.toAbsolutePath().normalize();
        this.platform = platform;
        this.releaseSource = releaseSource;
    }

    /** Release source for a local release folder made by {@code packaging/package.ps1}. */
    public static URI localReleaseSource(Path folder) {
        return folder.toAbsolutePath().normalize().resolve(LOCAL_RELEASE_FILE).toUri();
    }

    public Path dataRoot() {
        return dataRoot;
    }

    public Platform platform() {
        return platform;
    }

    public Path versionsDir() {
        return dataRoot.resolve("versions");
    }

    // ---------------------------------------------------------------- release lookup (network)

    /** Fetches the latest release (GitHub API or local release.json). */
    public Release latest() throws IOException, InterruptedException {
        String json;
        if ("file".equals(releaseSource.getScheme())) {
            json = Files.readString(Path.of(releaseSource), StandardCharsets.UTF_8);
        } else {
            HttpRequest request = HttpRequest.newBuilder(releaseSource)
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "Evolvia-Launcher")
                    .GET()
                    .build();
            HttpResponse<String> response = http().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 404) {
                throw new IOException("No release found (" + REPO + " has no published release, or the repository is private)");
            }
            if (response.statusCode() != 200) {
                throw new IOException("GitHub API returned HTTP " + response.statusCode());
            }
            json = response.body();
        }
        return parseRelease(json);
    }

    /** Parses a GitHub "release" JSON object ({@code tag_name}, {@code assets[]}). */
    static Release parseRelease(String json) throws IOException {
        ReleaseJson parsed;
        try {
            // Tolerate a UTF-8 byte order mark (files written by Windows tools).
            parsed = GSON.fromJson(json.startsWith("﻿") ? json.substring(1) : json, ReleaseJson.class);
        } catch (JsonParseException e) {
            throw new IOException("Invalid release JSON: " + e.getMessage(), e);
        }
        if (parsed == null || parsed.tagName() == null || parsed.tagName().isBlank()) {
            throw new IOException("Release JSON has no tag_name");
        }
        List<Release.Asset> assets = new ArrayList<>();
        if (parsed.assets() != null) {
            for (AssetJson asset : parsed.assets()) {
                if (asset.name() != null && asset.downloadUrl() != null) {
                    assets.add(new Release.Asset(asset.name(), URI.create(asset.downloadUrl()), asset.size()));
                }
            }
        }
        return new Release(parsed.tagName(), List.copyOf(assets));
    }

    /**
     * The game package of a release for a platform: name starts with {@code evolvia-},
     * is not the launcher, contains the platform key and has the platform's archive extension.
     */
    public static Optional<Release.Asset> assetFor(Release release, Platform platform) {
        String extension = archiveExtension(platform);
        return release.assets().stream()
                .filter(asset -> {
                    String name = asset.name().toLowerCase(Locale.ROOT);
                    return name.startsWith(PACKAGE_PREFIX)
                            && !name.contains("launcher")
                            && name.contains(platform.key())
                            && name.endsWith(extension);
                })
                .findFirst();
    }

    static String archiveExtension(Platform platform) {
        return platform == Platform.LINUX ? ".tar.gz" : ".zip";
    }

    // ---------------------------------------------------------------- versions (offline)

    /**
     * Compares version tags by their numeric parts: {@code v1.10 > v1.9}, {@code v1.2 == 1.2.0}.
     * Non-numeric text is ignored.
     */
    public static int compareVersions(String a, String b) {
        List<BigInteger> left = numbers(a);
        List<BigInteger> right = numbers(b);
        for (int i = 0; i < Math.max(left.size(), right.size()); i++) {
            BigInteger x = i < left.size() ? left.get(i) : BigInteger.ZERO;
            BigInteger y = i < right.size() ? right.get(i) : BigInteger.ZERO;
            int c = x.compareTo(y);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private static List<BigInteger> numbers(String version) {
        List<BigInteger> parts = new ArrayList<>();
        Matcher m = NUMBER.matcher(version);
        while (m.find()) {
            parts.add(new BigInteger(m.group()));
        }
        return parts;
    }

    /** True if {@code latest} should be installed over {@code installed} (null = nothing installed). */
    public static boolean isNewer(Release latest, String installed) {
        return installed == null || compareVersions(latest.tag(), installed) > 0;
    }

    /**
     * Installed version from {@code versions/current.txt}, or null if none is installed or its
     * folder no longer contains the game executable.
     */
    public String installedVersion() {
        Path current = versionsDir().resolve(CURRENT_FILE);
        if (!Files.isRegularFile(current)) {
            return null;
        }
        try {
            String tag = Files.readString(current, StandardCharsets.UTF_8).trim();
            return !tag.isEmpty() && isRunnable(executable(tag)) ? tag : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Folder of an installed version; the tag is reduced to safe characters because it becomes a folder name. */
    public Path installDir(String tag) {
        String safe = tag.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isEmpty() || safe.chars().allMatch(c -> c == '.')) {
            safe = "_" + safe;
        }
        return versionsDir().resolve(safe);
    }

    /** Game executable inside an installed version (jpackage app-image layout). */
    public Path executable(String tag) {
        Path dir = installDir(tag);
        return switch (platform) {
            case WINDOWS -> dir.resolve(APP_NAME).resolve(APP_NAME + ".exe");
            case MACOS -> dir.resolve(APP_NAME + ".app").resolve("Contents").resolve("MacOS").resolve(APP_NAME);
            case LINUX -> dir.resolve(APP_NAME).resolve("bin").resolve(APP_NAME);
        };
    }

    private boolean isRunnable(Path executable) {
        return Files.isRegularFile(executable) && (platform == Platform.WINDOWS || Files.isExecutable(executable));
    }

    // ---------------------------------------------------------------- download + install

    /** Downloads an asset into the versions folder and returns the file. */
    public Path download(Release.Asset asset, Progress progress) throws IOException, InterruptedException {
        Files.createDirectories(versionsDir());
        Path target = versionsDir().resolve("download-" + asset.name().replaceAll("[^A-Za-z0-9._-]", "_"));
        Path part = target.resolveSibling(target.getFileName() + ".part");
        try {
            if ("file".equals(asset.url().getScheme())) {
                Path source = Path.of(asset.url());
                try (InputStream in = Files.newInputStream(source)) {
                    copy(in, part, Files.size(source), progress);
                }
            } else {
                HttpRequest request = HttpRequest.newBuilder(asset.url())
                        .header("User-Agent", "Evolvia-Launcher")
                        .GET()
                        .build();
                HttpResponse<InputStream> response = http().send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream in = response.body()) {
                    if (response.statusCode() != 200) {
                        throw new IOException("Download failed: HTTP " + response.statusCode() + " for " + asset.name());
                    }
                    long total = response.headers().firstValueAsLong("Content-Length").orElse(asset.size() > 0 ? asset.size() : -1);
                    copy(in, part, total, progress);
                }
            }
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } finally {
            Files.deleteIfExists(part);
        }
    }

    private static void copy(InputStream in, Path target, long total, Progress progress) throws IOException {
        try (OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            long done = 0;
            int n;
            progress.update(0, total);
            while ((n = in.read(buffer)) >= 0) {
                out.write(buffer, 0, n);
                done += n;
                progress.update(done, total);
            }
        }
    }

    /**
     * Installs a downloaded package as {@code tag}: deletes the version folder, unpacks the archive,
     * checks that it contains the game executable, and only then records it in current.txt.
     * On failure the version folder is removed and current.txt keeps the previous version.
     */
    public void install(Path archive, String tag) throws IOException {
        Path dir = installDir(tag);
        deleteRecursively(dir);
        try {
            Archives.extract(archive, dir);
            if (!isRunnable(executable(tag))) {
                throw new IOException("Package does not contain the game executable " + versionsDir().relativize(executable(tag)));
            }
        } catch (IOException | RuntimeException e) {
            deleteRecursively(dir);
            throw e;
        }
        Path current = versionsDir().resolve(CURRENT_FILE);
        Path temp = current.resolveSibling(CURRENT_FILE + ".tmp");
        Files.writeString(temp, tag, StandardCharsets.UTF_8);
        Files.move(temp, current, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Starts an installed version as a separate process with the data folder as working directory.
     * The game gets the same data folder via {@code EVOLVIA_HOME}; its output goes to {@code logs/game.log}.
     */
    public Process launch(String tag) throws IOException {
        Path executable = executable(tag);
        if (!isRunnable(executable)) {
            throw new IOException("Version " + tag + " is not installed");
        }
        Path log = gameLog();
        Files.createDirectories(log.getParent());
        ProcessBuilder builder = new ProcessBuilder(executable.toString())
                .directory(dataRoot.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        builder.environment().put(GameDirs.HOME_ENV, dataRoot.toString());
        return builder.start();
    }

    public Path gameLog() {
        return dataRoot.resolve("logs").resolve("game.log");
    }

    static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private synchronized HttpClient http() {
        if (http == null) {
            http = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
        }
        return http;
    }

    /** JSON shape of a GitHub release (only the fields we use). */
    private record ReleaseJson(@SerializedName("tag_name") String tagName, List<AssetJson> assets) {
    }

    private record AssetJson(String name, @SerializedName("browser_download_url") String downloadUrl, long size) {
    }
}
