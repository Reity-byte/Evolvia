package evolvia.launcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds zip and tar.gz test archives, including things normal tools refuse to write
 * (zip slip names, Unix modes in zips, symlinks, GNU long names).
 */
final class TestArchives {

    /** Archive entry; {@code mode} = full Unix mode (type + permissions), 0 = none. */
    record Entry(String name, String content, int mode, char tarType, String linkTarget) {
        static Entry file(String name, String content) {
            return new Entry(name, content, 0100644, '0', null);
        }

        static Entry executable(String name, String content) {
            return new Entry(name, content, 0100755, '0', null);
        }

        static Entry dir(String name) {
            return new Entry(name, "", 0040755, '5', null);
        }

        static Entry symlink(String name, String target) {
            return new Entry(name, target, 0120777, '2', target);
        }
    }

    private TestArchives() {
    }

    /** Zip where every entry is marked as made on Unix with its mode (like ditto / Info-ZIP). */
    static Path zip(Path file, List<Entry> entries, boolean unixModes) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Entry entry : entries) {
                zip.putNextEntry(new ZipEntry(entry.name()));
                zip.write(entry.content().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        byte[] data = bytes.toByteArray();
        if (unixModes) {
            // Patch "version made by" and "external attributes" of each central directory record.
            ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            int index = 0;
            for (int p = 0; p + 46 <= data.length; p++) {
                if (buffer.getInt(p) == 0x02014b50) {
                    int nameLength = buffer.getShort(p + 28) & 0xFFFF;
                    buffer.putShort(p + 4, (short) ((3 << 8) | 20));
                    buffer.putInt(p + 38, entries.get(index++).mode() << 16);
                    p += 45 + nameLength;
                }
            }
        }
        Files.write(file, data);
        return file;
    }

    /** tar.gz in ustar format; names over 100 bytes get a GNU long-name ('L') entry. */
    static Path tarGz(Path file, List<Entry> entries) throws IOException {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        for (Entry entry : entries) {
            byte[] nameBytes = entry.name().getBytes(StandardCharsets.UTF_8);
            if (nameBytes.length > 100) {
                byte[] longName = (entry.name() + "\0").getBytes(StandardCharsets.UTF_8);
                tar.write(header("././@LongLink", 0644, longName.length, 'L', ""));
                writePadded(tar, longName);
            }
            byte[] content = entry.tarType() == '0' ? entry.content().getBytes(StandardCharsets.UTF_8) : new byte[0];
            tar.write(header(entry.name(), entry.mode() & 07777, content.length, entry.tarType(),
                    entry.linkTarget() != null ? entry.linkTarget() : ""));
            writePadded(tar, content);
        }
        tar.write(new byte[1024]); // end of archive
        try (GZIPOutputStream gzip = new GZIPOutputStream(Files.newOutputStream(file))) {
            gzip.write(tar.toByteArray());
        }
        return file;
    }

    private static byte[] header(String name, int mode, long size, char type, String link) {
        byte[] h = new byte[512];
        put(h, 0, 100, name);
        put(h, 100, 8, String.format("%07o", mode));
        put(h, 108, 8, "0000000");
        put(h, 116, 8, "0000000");
        put(h, 124, 12, String.format("%011o", size));
        put(h, 136, 12, "00000000000");
        h[156] = (byte) type;
        put(h, 157, 100, link);
        put(h, 257, 6, "ustar");
        put(h, 263, 2, "00");
        for (int i = 148; i < 156; i++) {
            h[i] = ' ';
        }
        int checksum = 0;
        for (byte b : h) {
            checksum += b & 0xFF;
        }
        put(h, 148, 7, String.format("%06o", checksum));
        return h;
    }

    private static void put(byte[] header, int offset, int length, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, header, offset, Math.min(bytes.length, length));
    }

    private static void writePadded(ByteArrayOutputStream out, byte[] data) {
        out.writeBytes(data);
        int padding = (512 - data.length % 512) % 512;
        out.writeBytes(new byte[padding]);
    }
}
