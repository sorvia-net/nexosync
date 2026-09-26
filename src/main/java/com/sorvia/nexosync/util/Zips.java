package com.sorvia.nexosync.util;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * ZIP packaging and extraction with the safety rules the snapshot format depends on.
 *
 * <p>Extraction is deliberately strict. An archive downloaded from the internet is never trusted:
 * entry names are validated before anything is created on disk, the resolved target is checked
 * against the staging root, and both the entry count and the uncompressed size are capped so a
 * malicious or corrupt archive cannot exhaust the disk.</p>
 */
public final class Zips {

    /** Fixed entry timestamp so identical content always produces an identical archive. */
    private static final long FIXED_ENTRY_TIME = 0L;

    /**
     * Maximum tolerated ratio between uncompressed and compressed bytes for a single entry. Normal
     * YAML and resource-pack content stays far below this; a decompression bomb does not.
     */
    private static final long MAX_COMPRESSION_RATIO = 250L;

    private Zips() {
    }

    /**
     * Creates a deterministic archive. Entries are written in sorted order with a fixed timestamp,
     * so the same input directory always yields byte-identical output.
     *
     * @param entries archive path to source file, archive paths must be relative and forward-slashed
     */
    public static void create(Path archive, Map<String, Path> entries) throws IOException {
        Files.createDirectories(archive.getParent());
        Map<String, Path> sorted = new TreeMap<>(entries);
        try (OutputStream fileOut = Files.newOutputStream(archive);
             BufferedOutputStream buffered = new BufferedOutputStream(fileOut, 64 * 1024);
             ZipOutputStream zip = new ZipOutputStream(buffered, StandardCharsets.UTF_8)) {

            zip.setLevel(Deflater.BEST_COMPRESSION);
            for (Map.Entry<String, Path> entry : sorted.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zipEntry.setTime(FIXED_ENTRY_TIME);
                zip.putNextEntry(zipEntry);
                Files.copy(entry.getValue(), zip);
                zip.closeEntry();
            }
        }
    }

    /**
     * Adds a single in-memory document (the manifest) to an entry map by writing it to a temporary
     * file first, because {@link #create(Path, Map)} only accepts real files.
     */
    public static Path writeTemporary(Path directory, String name, byte[] content) throws IOException {
        Files.createDirectories(directory);
        Path file = directory.resolve(name);
        Files.write(file, content);
        return file;
    }

    /**
     * Reads a single entry from an archive without extracting anything.
     *
     * @return the entry content, or null when the entry does not exist
     */
    public static byte[] readEntry(Path archive, String entryName, long maxBytes) throws IOException {
        try (ZipFile zipFile = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry entry = zipFile.getEntry(entryName);
            if (entry == null) {
                return null;
            }
            if (entry.getSize() > maxBytes) {
                throw new IOException("Archive entry " + entryName + " exceeds the allowed size");
            }
            try (InputStream in = zipFile.getInputStream(entry);
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                long total = 0L;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    total += read;
                    if (total > maxBytes) {
                        throw new IOException("Archive entry " + entryName + " exceeds the allowed size");
                    }
                    out.write(buffer, 0, read);
                }
                return out.toByteArray();
            }
        }
    }

    /**
     * Inspects an archive without writing to disk, applying the configured limits.
     */
    public static List<String> listEntries(Path archive, int maxFileCount) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipFile zipFile = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            var iterator = zipFile.entries();
            while (iterator.hasMoreElements()) {
                ZipEntry entry = iterator.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                names.add(entry.getName());
                if (names.size() > maxFileCount) {
                    throw new IOException("Archive contains more than " + maxFileCount + " files");
                }
            }
        }
        return names;
    }

    /**
     * Extracts an archive into a target directory.
     *
     * <p>Every entry is validated before it is created. Any entry that is absolute, contains a
     * {@code ..} segment, or resolves outside the target directory aborts the whole extraction.</p>
     */
    public static ExtractionResult extract(Path archive, Path target, int maxFileCount, long maxTotalBytes)
            throws IOException {

        Files.createDirectories(target);
        Path targetRoot = target.toAbsolutePath().normalize();

        int fileCount = 0;
        long totalBytes = 0L;
        List<String> extracted = new ArrayList<>();

        try (ZipFile zipFile = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            var iterator = zipFile.entries();
            while (iterator.hasMoreElements()) {
                ZipEntry entry = iterator.nextElement();
                String name = FileUtils.normalize(entry.getName());

                if (entry.isDirectory()) {
                    requireSafeName(name.endsWith("/") ? name.substring(0, name.length() - 1) : name);
                    continue;
                }

                requireSafeName(name);

                Path destination = targetRoot.resolve(name).normalize();
                if (!destination.startsWith(targetRoot)) {
                    throw new IOException("Archive entry escapes the staging directory: " + entry.getName());
                }

                fileCount++;
                if (fileCount > maxFileCount) {
                    throw new IOException("Archive contains more than " + maxFileCount + " files");
                }

                Files.createDirectories(destination.getParent());

                long written;
                try (InputStream in = zipFile.getInputStream(entry);
                     OutputStream out = Files.newOutputStream(destination)) {
                    written = copyBounded(in, out, maxTotalBytes - totalBytes);
                }

                long compressed = entry.getCompressedSize();
                if (compressed > 0 && written / compressed > MAX_COMPRESSION_RATIO) {
                    throw new IOException("Archive entry has a suspicious compression ratio: " + entry.getName());
                }

                totalBytes += written;
                extracted.add(name);
            }
        }

        return new ExtractionResult(fileCount, totalBytes, extracted);
    }

    private static void requireSafeName(String name) throws IOException {
        if (!FileUtils.isSafeRelativePath(name)) {
            throw new IOException("Archive contains an unsafe entry path: " + name);
        }
    }

    private static long copyBounded(InputStream in, OutputStream out, long remainingAllowance) throws IOException {
        byte[] buffer = new byte[8192];
        long written = 0L;
        int read;
        while ((read = in.read(buffer)) != -1) {
            written += read;
            if (written > remainingAllowance) {
                throw new IOException("Archive exceeds the configured maximum uncompressed size");
            }
            out.write(buffer, 0, read);
        }
        return written;
    }

    /**
     * Summary of a completed extraction.
     */
    public record ExtractionResult(int fileCount, long totalBytes, List<String> entries) {
    }
}
