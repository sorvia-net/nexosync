package com.sorvia.nexosync.engine;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * One stored backup of the managed directories.
 *
 * <p>The identifier encodes both the snapshot version and the moment the backup was taken, so a
 * version that was installed twice still produces two distinct, orderable entries.</p>
 */
public record BackupEntry(String id, int version, Instant createdAt, Path location, boolean compressed) {

    public static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public static String buildId(int version, Instant moment) {
        return "v" + version + "-" + STAMP.format(LocalDateTime.ofInstant(moment, ZoneId.systemDefault()));
    }

    /**
     * Parses a backup identifier back into its version and timestamp.
     *
     * @return empty when the name does not follow the NexoSync backup scheme
     */
    public static Optional<BackupEntry> parse(Path location) {
        String name = location.getFileName().toString();
        boolean compressed = name.endsWith(".zip");
        String id = compressed ? name.substring(0, name.length() - 4) : name;

        if (!id.startsWith("v")) {
            return Optional.empty();
        }
        int separator = id.indexOf('-');
        if (separator < 2) {
            return Optional.empty();
        }
        try {
            int version = Integer.parseInt(id.substring(1, separator));
            LocalDateTime moment = LocalDateTime.parse(id.substring(separator + 1), STAMP);
            return Optional.of(new BackupEntry(id, version, moment.atZone(ZoneId.systemDefault()).toInstant(),
                    location, compressed));
        } catch (RuntimeException notABackup) {
            return Optional.empty();
        }
    }

    public String display() {
        return id + (compressed ? " (compressed)" : "");
    }
}
