package evolvia.launcher;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Enumeration;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Safe extraction of release packages: {@code .zip} (Windows, macOS) and {@code .tar.gz} (Linux).
 * <ul>
 *   <li>Zip slip: an entry (or symlink target) that would end up outside the target folder
 *       rejects the whole archive.</li>
 *   <li>Zips made by PowerShell 5.1 {@code Compress-Archive} use backslashes ({@code dir\file},
 *       folders as {@code dir\}); they are converted to {@code /} first.</li>
 *   <li>Unix permissions (executable bits) and symlinks are restored where the archive stores them
 *       (zip from {@code ditto}, tar), because the packaged app will not start without them.</li>
 * </ul>
 */
public final class Archives {

    private static final int S_IFMT = 0170000;
    private static final int S_IFLNK = 0120000;
    private static final int S_IFDIR = 0040000;
    private static final int TAR_BLOCK = 512;

    private Archives() {
    }

    /** Extracts {@code archive} into {@code target} (created if needed); format by file name. */
    public static void extract(Path archive, Path target) throws IOException {
        Files.createDirectories(target);
        Path root = target.toAbsolutePath().normalize();
        String name = archive.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
            untarGz(archive, root);
        } else if (name.endsWith(".zip")) {
            unzip(archive, root);
        } else {
            throw new IOException("Unsupported archive type: " + archive.getFileName());
        }
    }

    // ---------------------------------------------------------------- zip

    private static void unzip(Path zip, Path root) throws IOException {
        Map<String, Integer> unixModes = readZipUnixModes(zip);
        try (ZipFile file = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = normalizeName(entry.getName());
                if (name.isEmpty()) {
                    continue;
                }
                Path path = resolveInside(root, name);
                int mode = unixModes.getOrDefault(entry.getName(), 0);

                if (name.endsWith("/") || (mode & S_IFMT) == S_IFDIR) {
                    Files.createDirectories(path);
                } else if ((mode & S_IFMT) == S_IFLNK) {
                    String linkTarget;
                    try (InputStream in = file.getInputStream(entry)) {
                        linkTarget = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    }
                    createSymlink(root, path, linkTarget);
                } else {
                    Files.createDirectories(path.getParent());
                    try (InputStream in = file.getInputStream(entry)) {
                        Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
                    }
                    applyMode(path, mode);
                }
            }
        }
    }

    /**
     * Reads Unix mode bits (file type + permissions) per entry name from the zip central directory.
     * {@link ZipFile} does not expose them. Entries not made on Unix (e.g. PowerShell zips) are absent.
     */
    static Map<String, Integer> readZipUnixModes(Path zip) throws IOException {
        Map<String, Integer> modes = new HashMap<>();
        try (FileChannel channel = FileChannel.open(zip, StandardOpenOption.READ)) {
            long size = channel.size();
            int tailLength = (int) Math.min(size, 22 + 65535);
            ByteBuffer tail = ByteBuffer.allocate(tailLength).order(ByteOrder.LITTLE_ENDIAN);
            channel.read(tail, size - tailLength);

            int eocd = -1;
            for (int i = tailLength - 22; i >= 0; i--) {
                if (tail.getInt(i) == 0x06054b50) {
                    eocd = i;
                    break;
                }
            }
            if (eocd < 0) {
                throw new IOException("Not a zip file (no end of central directory): " + zip.getFileName());
            }
            int entryCount = tail.getShort(eocd + 10) & 0xFFFF;
            long cdSize = tail.getInt(eocd + 12) & 0xFFFFFFFFL;
            long cdOffset = tail.getInt(eocd + 16) & 0xFFFFFFFFL;
            if (cdOffset == 0xFFFFFFFFL || cdSize > Integer.MAX_VALUE) {
                return modes; // ZIP64: not used for our packages; extract without modes
            }

            ByteBuffer cd = ByteBuffer.allocate((int) cdSize).order(ByteOrder.LITTLE_ENDIAN);
            channel.read(cd, cdOffset);
            int p = 0;
            for (int n = 0; n < entryCount && p + 46 <= cdSize; n++) {
                if (cd.getInt(p) != 0x02014b50) {
                    break;
                }
                int madeBy = (cd.getShort(p + 4) & 0xFFFF) >> 8;
                int nameLength = cd.getShort(p + 28) & 0xFFFF;
                int extraLength = cd.getShort(p + 30) & 0xFFFF;
                int commentLength = cd.getShort(p + 32) & 0xFFFF;
                int externalAttributes = cd.getInt(p + 38);
                byte[] nameBytes = new byte[nameLength];
                cd.get(p + 46, nameBytes);
                if (madeBy == 3) { // 3 = Unix
                    modes.put(new String(nameBytes, StandardCharsets.UTF_8), externalAttributes >>> 16);
                }
                p += 46 + nameLength + extraLength + commentLength;
            }
        }
        return modes;
    }

    // ---------------------------------------------------------------- tar.gz

    private static void untarGz(Path archive, Path root) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(archive))) {
            byte[] header = new byte[TAR_BLOCK];
            String longName = null;
            String longLink = null;
            while (true) {
                if (!readBlock(in, header)) {
                    break;
                }
                if (isZeroBlock(header)) {
                    break; // end of archive
                }
                String name = tarString(header, 0, 100);
                int mode = (int) tarOctal(header, 100, 8);
                long size = tarOctal(header, 124, 12);
                char type = (char) header[156];
                String linkName = tarString(header, 157, 100);
                if (tarString(header, 257, 5).equals("ustar")) {
                    String prefix = tarString(header, 345, 155);
                    if (!prefix.isEmpty()) {
                        name = prefix + "/" + name;
                    }
                }

                switch (type) {
                    case 'L' -> { // GNU long name for the next entry
                        longName = trimNul(readString(in, size));
                        continue;
                    }
                    case 'K' -> { // GNU long link name for the next entry
                        longLink = trimNul(readString(in, size));
                        continue;
                    }
                    case 'x' -> { // PAX extended header for the next entry
                        Map<String, String> pax = parsePax(readString(in, size));
                        longName = pax.getOrDefault("path", longName);
                        longLink = pax.getOrDefault("linkpath", longLink);
                        continue;
                    }
                    case 'g' -> { // PAX global header: nothing we need
                        skip(in, padded(size));
                        continue;
                    }
                    default -> {
                    }
                }
                if (longName != null) {
                    name = longName;
                    longName = null;
                }
                if (longLink != null) {
                    linkName = longLink;
                    longLink = null;
                }

                name = normalizeName(name);
                if (name.isEmpty() || name.equals("./")) {
                    skip(in, padded(size));
                    continue;
                }
                Path path = resolveInside(root, name);
                switch (type) {
                    case '5' -> {
                        Files.createDirectories(path);
                        skip(in, padded(size));
                    }
                    case '2' -> {
                        createSymlink(root, path, linkName);
                        skip(in, padded(size));
                    }
                    case '1' -> { // hard link: copy the already extracted file
                        Path source = resolveInside(root, normalizeName(linkName));
                        Files.createDirectories(path.getParent());
                        Files.copy(source, path, StandardCopyOption.REPLACE_EXISTING);
                        skip(in, padded(size));
                    }
                    case '0', '\0', '7' -> {
                        Files.createDirectories(path.getParent());
                        Files.copy(new BoundedInputStream(in, size), path, StandardCopyOption.REPLACE_EXISTING);
                        skip(in, padded(size) - size);
                        applyMode(path, mode);
                    }
                    default -> skip(in, padded(size)); // devices, fifos, ...: not expected, ignored
                }
            }
        }
    }

    private static boolean readBlock(InputStream in, byte[] block) throws IOException {
        int read = in.readNBytes(block, 0, block.length);
        if (read == 0) {
            return false;
        }
        if (read < block.length) {
            throw new EOFException("Truncated tar archive");
        }
        return true;
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static String tarString(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static long tarOctal(byte[] header, int offset, int length) throws IOException {
        String text = tarString(header, offset, length).trim();
        if (text.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(text, 8);
        } catch (NumberFormatException e) {
            throw new IOException("Corrupt tar header field: '" + text + "'");
        }
    }

    private static String readString(InputStream in, long size) throws IOException {
        if (size > 1 << 20) {
            throw new IOException("Tar extended header too large: " + size);
        }
        byte[] data = in.readNBytes((int) size);
        if (data.length < size) {
            throw new EOFException("Truncated tar archive");
        }
        skip(in, padded(size) - size);
        return new String(data, StandardCharsets.UTF_8);
    }

    private static String trimNul(String s) {
        int nul = s.indexOf('\0');
        return nul >= 0 ? s.substring(0, nul) : s;
    }

    /** PAX records: "{length} {key}={value}\n". */
    private static Map<String, String> parsePax(String data) {
        Map<String, String> values = new HashMap<>();
        for (String line : data.split("\n")) {
            int space = line.indexOf(' ');
            int equals = line.indexOf('=');
            if (space > 0 && equals > space) {
                values.put(line.substring(space + 1, equals), line.substring(equals + 1));
            }
        }
        return values;
    }

    private static long padded(long size) {
        return (size + TAR_BLOCK - 1) / TAR_BLOCK * TAR_BLOCK;
    }

    private static void skip(InputStream in, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    throw new EOFException("Truncated tar archive");
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    /** Stream view of the next {@code limit} bytes; does not close the underlying stream. */
    private static final class BoundedInputStream extends InputStream {
        private final InputStream in;
        private long remaining;

        BoundedInputStream(InputStream in, long limit) {
            this.in = in;
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int b = in.read();
            if (b < 0) {
                throw new EOFException("Truncated tar archive");
            }
            remaining--;
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int n = in.read(buffer, offset, (int) Math.min(length, remaining));
            if (n < 0) {
                throw new EOFException("Truncated tar archive");
            }
            remaining -= n;
            return n;
        }
    }

    // ---------------------------------------------------------------- shared

    /** Converts backslashes (PowerShell 5.1 zips) to slashes and drops a leading "./". */
    static String normalizeName(String name) {
        String normalized = name.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    /** Resolves an entry name under {@code root}; rejects the archive if it would escape (zip slip). */
    static Path resolveInside(Path root, String name) throws IOException {
        Path relative;
        try {
            relative = Path.of(name.endsWith("/") ? name.substring(0, name.length() - 1) : name);
        } catch (InvalidPathException e) {
            throw new IOException("Invalid archive entry name, archive rejected: " + name, e);
        }
        Path resolved = root.resolve(relative).normalize();
        if (relative.isAbsolute() || name.startsWith("/") || !resolved.startsWith(root) || resolved.equals(root)) {
            throw new IOException("Archive entry points outside the target folder, archive rejected: " + name);
        }
        return resolved;
    }

    private static void createSymlink(Path root, Path link, String linkTarget) throws IOException {
        Path target;
        try {
            target = Path.of(linkTarget.replace('\\', '/'));
        } catch (InvalidPathException e) {
            throw new IOException("Invalid symlink target, archive rejected: " + linkTarget, e);
        }
        Path resolved = link.getParent().resolve(target).normalize();
        if (target.isAbsolute() || linkTarget.startsWith("/") || !resolved.startsWith(root)) {
            throw new IOException("Symlink points outside the target folder, archive rejected: "
                    + root.relativize(link) + " -> " + linkTarget);
        }
        Files.createDirectories(link.getParent());
        Files.deleteIfExists(link);
        Files.createSymbolicLink(link, target);
    }

    /** Applies Unix permission bits where the file system supports them (not on Windows). */
    private static void applyMode(Path file, int mode) throws IOException {
        int permissions = mode & 0777;
        if (permissions == 0 || !FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        Set<PosixFilePermission> set = EnumSet.noneOf(PosixFilePermission.class);
        PosixFilePermission[] order = {
                PosixFilePermission.OTHERS_EXECUTE, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_READ,
                PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_READ,
                PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_READ,
        };
        for (int bit = 0; bit < 9; bit++) {
            if ((permissions & (1 << bit)) != 0) {
                set.add(order[bit]);
            }
        }
        Files.setPosixFilePermissions(file, set);
    }
}
