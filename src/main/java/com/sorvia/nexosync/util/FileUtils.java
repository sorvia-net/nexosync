package com.sorvia.nexosync.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Filesystem helpers shared by the snapshot, backup and installation code.
 *
 * <p>All directory walks reject symbolic links by default: a managed directory is expected to
 * contain plain files, and a link is the easiest way to make a snapshot reach outside its scope.</p>
 */
public final class FileUtils {

    private FileUtils() {
    }

    public static void ensureDirectory(Path directory) throws IOException {
        Files.createDirectories(directory);
    }

    public static void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(path);
            return;
        }
        try (var stream = Files.walk(path)) {
            List<Path> entries = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path entry : entries) {
                Files.deleteIfExists(entry);
            }
        }
    }

    public static void copyDirectory(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path destination = target.resolve(source.relativize(file).toString());
                Files.createDirectories(destination.getParent());
                Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Lists every regular file below the given root, returned as forward-slash paths relative to it.
     *
     * @throws IOException when a symbolic link is encountered and links are rejected
     */
    public static List<String> listRelativeFiles(Path root, boolean rejectSymbolicLinks) throws IOException {
        List<String> results = new ArrayList<>();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return results;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (rejectSymbolicLinks && Files.isSymbolicLink(dir)) {
                    throw new IOException("Symbolic link is not allowed inside a managed directory: " + dir);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(file)) {
                    if (rejectSymbolicLinks) {
                        throw new IOException("Symbolic link is not allowed inside a managed directory: " + file);
                    }
                    return FileVisitResult.CONTINUE;
                }
                if (attrs.isRegularFile()) {
                    results.add(normalize(root.relativize(file).toString()));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        results.sort(Comparator.naturalOrder());
        return results;
    }

    public static long directorySize(Path root) throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return 0L;
        }
        try (var stream = Files.walk(root)) {
            long total = 0L;
            for (Path entry : stream.toList()) {
                if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    total += Files.size(entry);
                }
            }
            return total;
        }
    }

    /**
     * Writes a file without ever leaving a half-written document behind: content goes to a sibling
     * temporary file first and is then moved into place.
     */
    public static void writeAtomic(Path target, byte[] content) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temporary, content);
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveUnsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void writeAtomic(Path target, String content) throws IOException {
        writeAtomic(target, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Returns true when the candidate resolves inside the base directory. Both paths are normalised
     * to absolute form first, which is what makes a traversal escape detectable.
     */
    public static boolean isWithin(Path base, Path candidate) {
        Path normalizedBase = base.toAbsolutePath().normalize();
        Path normalizedCandidate = candidate.toAbsolutePath().normalize();
        return normalizedCandidate.startsWith(normalizedBase);
    }

    public static String normalize(String relativePath) {
        return relativePath.replace('\\', '/');
    }

    /**
     * Rejects relative paths that are absolute, empty, or that try to climb out of their root.
     */
    public static boolean isSafeRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return false;
        }
        String normalized = normalize(relativePath);
        if (normalized.startsWith("/") || normalized.indexOf('\0') >= 0) {
            return false;
        }
        if (normalized.length() > 1 && normalized.charAt(1) == ':') {
            return false;
        }
        for (String segment : normalized.split("/")) {
            if (segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    public static String humanReadableSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int index = -1;
        while (value >= 1024 && index < units.length - 1) {
            value /= 1024;
            index++;
        }
        return String.format(Locale.ROOT, "%.2f %s", value, units[index]);
    }
}
