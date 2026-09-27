package evolvia.save;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.stream.JsonReader;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Save game files: {@code <folder>/<name>.evsave}, gzip-compressed JSON of {@link SaveData}. Writing goes
 * to a temporary file first and is then moved over the old save, so a crash while saving never destroys
 * it. The folder is passed in (from {@code GameDirs} at runtime), never computed statically.
 */
public final class SaveManager implements AutoCloseable {

    public static final String EXTENSION = ".evsave";
    public static final String QUICK_SAVE = "rychle";
    public static final String AUTOSAVE = "autosave";

    private static final Gson GSON = new GsonBuilder().serializeSpecialFloatingPointValues().create();

    private final Path folder;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "save-writer");
        thread.setDaemon(true);
        return thread;
    });

    /** A save in the list, with the info needed to show it. */
    public record SaveInfo(String name, Path file, SaveData.Meta meta, int saveVersion, FileTime modified) {
    }

    public SaveManager(Path folder) {
        this.folder = folder;
    }

    public Path folder() {
        return folder;
    }

    /** File of a save; the name is cleaned to letters, digits, spaces, '-' and '_'. */
    public Path file(String name) {
        return folder.resolve(cleanName(name) + EXTENSION);
    }

    static String cleanName(String name) {
        String cleaned = name.strip().replaceAll("[^\\p{L}\\p{N} _-]", "_");
        return cleaned.isEmpty() ? "save" : cleaned;
    }

    /** Writes a save now (blocking). */
    public Path save(SaveData data) throws IOException {
        Files.createDirectories(folder);
        Path target = file(data.meta().name());
        Path temp = folder.resolve(target.getFileName() + ".tmp");
        try (Writer out = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(temp)), StandardCharsets.UTF_8)) {
            GSON.toJson(data, out);
        }
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    /**
     * Writes a save on a background thread (the snapshot is already taken, so the game can continue).
     * Saves are written one after another in the order requested.
     */
    public CompletableFuture<Path> saveAsync(SaveData data) {
        CompletableFuture<Path> result = new CompletableFuture<>();
        writer.execute(() -> {
            try {
                result.complete(save(data));
            } catch (IOException | RuntimeException e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    /**
     * Reads a whole save.
     *
     * @throws SaveException (in Czech, for the player) if it is missing or cannot be read
     */
    public SaveData load(String name) {
        Path file = file(name);
        if (!Files.isRegularFile(file)) {
            throw new SaveException("Save „" + name + "“ neexistuje");
        }
        try (Reader in = reader(file)) {
            SaveData data = GSON.fromJson(in, SaveData.class);
            if (data == null) {
                throw new SaveException("Save „" + name + "“ je prázdný");
            }
            return data;
        } catch (IOException | JsonParseException e) {
            throw new SaveException("Save „" + name + "“ nejde přečíst: " + e.getMessage(), e);
        }
    }

    /** All saves, newest first. Only the beginning of each file is read. Unreadable files are skipped. */
    public List<SaveInfo> list() {
        List<SaveInfo> saves = new ArrayList<>();
        if (!Files.isDirectory(folder)) {
            return saves;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*" + EXTENSION)) {
            for (Path file : files) {
                SaveInfo info = readInfo(file);
                if (info != null) {
                    saves.add(info);
                }
            }
        } catch (IOException e) {
            return saves;
        }
        saves.sort(Comparator.comparing(SaveInfo::modified).reversed());
        return saves;
    }

    private static SaveInfo readInfo(Path file) {
        String fileName = file.getFileName().toString();
        String name = fileName.substring(0, fileName.length() - EXTENSION.length());
        try (JsonReader json = new JsonReader(reader(file))) {
            int version = -1;
            SaveData.Meta meta = null;
            json.beginObject();
            while (json.hasNext() && (version < 0 || meta == null)) {
                switch (json.nextName()) {
                    case "saveVersion" -> version = json.nextInt();
                    case "meta" -> meta = GSON.fromJson(json, SaveData.Meta.class);
                    default -> json.skipValue();
                }
            }
            return meta != null ? new SaveInfo(name, file, meta, version, Files.getLastModifiedTime(file)) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Deletes a save (the player confirmed it in the menu). */
    public void delete(String name) throws IOException {
        Files.deleteIfExists(file(name));
    }

    private static Reader reader(Path file) throws IOException {
        return new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8);
    }

    /** Waits until all saves requested so far are written. */
    public void flush() {
        try {
            writer.submit(() -> { }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException(e);
        }
    }

    /** JSON of a save (tests). */
    static String toJson(SaveData data) {
        return GSON.toJson(data);
    }

    /** Save from JSON (tests). */
    static SaveData fromJson(String json) {
        return GSON.fromJson(json, SaveData.class);
    }

    /** Waits for pending writes (e.g. the autosave on quit), at most a few seconds. */
    @Override
    public void close() {
        writer.shutdown();
        try {
            writer.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
