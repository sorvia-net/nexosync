package com.sorvia.nexosync.engine;

import com.sorvia.nexosync.NexoSyncException;
import com.sorvia.nexosync.config.NexoSyncConfig;
import com.sorvia.nexosync.log.NexoSyncLogger;
import com.sorvia.nexosync.snapshot.SnapshotManifest;
import com.sorvia.nexosync.util.FileUtils;
import com.sorvia.nexosync.util.Zips;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Creates and restores backups of the managed Nexo directories.
 *
 * <p>A backup is taken before the first live file is deleted, and a failure to create one aborts the
 * update. That ordering is the whole reason a bad snapshot is survivable: there is always a known
 * good copy on disk before anything is removed.</p>
 */
public final class BackupManager {

    private final NexoSyncConfig config;
    private final Path nexoDirectory;
    private final Path backupDirectory;
    private final NexoSyncLogger log;

    public BackupManager(NexoSyncConfig config, Path nexoDirectory, Path backupDirectory, NexoSyncLogger log) {
        this.config = config;
        this.nexoDirectory = nexoDirectory;
        this.backupDirectory = backupDirectory;
        this.log = log;
    }

    public Path directory() {
        return backupDirectory;
    }

    // -----------------------------------------------------------------------------------------
    // Creating
    // -----------------------------------------------------------------------------------------

    /**
     * Copies the current managed content into the backup directory.
     *
     * @param version  snapshot version currently installed, or 0 when nothing was installed yet
     * @param manifest manifest of the installed snapshot, stored alongside the files when known
     */
    public BackupEntry create(String operationId, int version, SnapshotManifest manifest) throws NexoSyncException {
        Instant now = Instant.now();
        String id = BackupEntry.buildId(version, now);

        try {
            FileUtils.ensureDirectory(backupDirectory);
            Map<String, Path> content = collectLiveFiles();

            if (manifest != null) {
                Path manifestCopy = Zips.writeTemporary(
                        backupDirectory.resolve(".staging"), SnapshotManifest.FILE_NAME, manifest.toJsonBytes());
                content.put(SnapshotManifest.FILE_NAME, manifestCopy);
            }

            BackupEntry entry;
            if (config.backup().compress()) {
                Path archive = backupDirectory.resolve(id + ".zip");
                Zips.create(archive, content);
                entry = new BackupEntry(id, version, now, archive, true);
            } else {
                Path directory = backupDirectory.resolve(id);
                FileUtils.ensureDirectory(directory);
                for (Map.Entry<String, Path> file : content.entrySet()) {
                    Path destination = directory.resolve(file.getKey());
                    Files.createDirectories(destination.getParent());
                    Files.copy(file.getValue(), destination);
                }
                entry = new BackupEntry(id, version, now, directory, false);
            }

            FileUtils.deleteRecursively(backupDirectory.resolve(".staging"));

            long size = entry.compressed() ? Files.size(entry.location()) : FileUtils.directorySize(entry.location());
            log.progress(operationId, "Backup " + entry.id() + " created ("
                    + content.size() + " files, " + FileUtils.humanReadableSize(size) + ").");
            return entry;

        } catch (IOException backupFailed) {
            throw new NexoSyncException(OperationStage.BACKUP,
                    "Unable to create a backup of the current snapshot: " + backupFailed.getMessage(), backupFailed);
        }
    }

    private Map<String, Path> collectLiveFiles() throws IOException {
        Map<String, Path> content = new LinkedHashMap<>();
        for (String managed : config.sync().paths()) {
            Path root = nexoDirectory.resolve(managed).normalize();
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            for (String relative : FileUtils.listRelativeFiles(root, config.safety().rejectSymbolicLinks())) {
                content.put(managed + "/" + relative, root.resolve(relative));
            }
        }
        return content;
    }

    // -----------------------------------------------------------------------------------------
    // Restoring
    // -----------------------------------------------------------------------------------------

    /**
     * Replaces the managed directories with the content of a backup.
     *
     * <p>The backup is unpacked into a temporary directory first, so a corrupt archive is discovered
     * before the live directories are removed.</p>
     */
    public void restore(String operationId, BackupEntry entry, Path workDirectory) throws NexoSyncException {
        Path staged = workDirectory.resolve("restore-" + entry.id());
        try {
            FileUtils.deleteRecursively(staged);
            FileUtils.ensureDirectory(staged);

            if (entry.compressed()) {
                Zips.extract(entry.location(), staged,
                        config.safety().maxFileCount(), config.safety().maxUncompressedBytes());
            } else {
                FileUtils.copyDirectory(entry.location(), staged);
            }

            Files.deleteIfExists(staged.resolve(SnapshotManifest.FILE_NAME));

            for (String managed : config.sync().paths()) {
                Path live = nexoDirectory.resolve(managed).normalize();
                if (!FileUtils.isWithin(nexoDirectory, live)) {
                    throw new NexoSyncException(OperationStage.ROLLBACK,
                            "Refusing to restore outside the Nexo directory: " + managed);
                }
                FileUtils.deleteRecursively(live);

                Path source = staged.resolve(managed);
                if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                    FileUtils.ensureDirectory(live);
                    FileUtils.copyDirectory(source, live);
                } else {
                    // The backup legitimately had nothing here; recreate the empty directory.
                    FileUtils.ensureDirectory(live);
                }
            }

            log.info(operationId, "Backup " + entry.id() + " restored.");

        } catch (IOException restoreFailed) {
            throw new NexoSyncException(OperationStage.ROLLBACK,
                    "Unable to restore backup " + entry.id() + ": " + restoreFailed.getMessage(), restoreFailed);
        } finally {
            try {
                FileUtils.deleteRecursively(staged);
            } catch (IOException ignored) {
                // Temporary content only; a leftover directory is cleaned on the next run.
            }
        }
    }

    /**
     * Reads the manifest stored inside a backup, when one is present.
     */
    public Optional<SnapshotManifest> readManifest(BackupEntry entry) {
        try {
            byte[] document;
            if (entry.compressed()) {
                document = Zips.readEntry(entry.location(), SnapshotManifest.FILE_NAME, 32L * 1024 * 1024);
            } else {
                Path file = entry.location().resolve(SnapshotManifest.FILE_NAME);
                document = Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
            }
            return document == null ? Optional.empty() : Optional.of(SnapshotManifest.fromJson(document));
        } catch (IOException | RuntimeException unreadable) {
            log.debug(null, "Backup " + entry.id() + " has no readable manifest: " + unreadable.getMessage());
            return Optional.empty();
        }
    }

    // -----------------------------------------------------------------------------------------
    // Listing and pruning
    // -----------------------------------------------------------------------------------------

    /**
     * Returns every stored backup, newest first.
     */
    public List<BackupEntry> list() {
        if (!Files.isDirectory(backupDirectory)) {
            return List.of();
        }
        List<BackupEntry> entries = new ArrayList<>();
        try (var stream = Files.list(backupDirectory)) {
            for (Path candidate : stream.toList()) {
                BackupEntry.parse(candidate).ifPresent(entries::add);
            }
        } catch (IOException listFailed) {
            log.warn(null, "Unable to list the backup directory: " + listFailed.getMessage());
            return List.of();
        }
        entries.sort(Comparator.comparing(BackupEntry::createdAt).reversed());
        return entries;
    }

    public Optional<BackupEntry> latest() {
        List<BackupEntry> entries = list();
        return entries.isEmpty() ? Optional.empty() : Optional.of(entries.get(0));
    }

    /**
     * Finds the most recent backup taken while the given snapshot version was installed.
     */
    public Optional<BackupEntry> findByVersion(int version) {
        return list().stream().filter(entry -> entry.version() == version).findFirst();
    }

    public Optional<BackupEntry> findById(String id) {
        return list().stream().filter(entry -> entry.id().equalsIgnoreCase(id)).findFirst();
    }

    /**
     * Deletes the oldest backups beyond the configured retention count.
     */
    public void prune(String operationId) {
        List<BackupEntry> entries = list();
        int keep = config.backup().keepLast();
        if (entries.size() <= keep) {
            return;
        }
        for (BackupEntry entry : entries.subList(keep, entries.size())) {
            try {
                FileUtils.deleteRecursively(entry.location());
                log.debug(operationId, "Pruned backup " + entry.id() + ".");
            } catch (IOException deleteFailed) {
                log.warn(operationId, "Unable to prune backup " + entry.id() + ": " + deleteFailed.getMessage());
            }
        }
    }

    /**
     * Lowest snapshot version still reachable through a stored backup.
     */
    public int oldestRetainedVersion() {
        return list().stream().mapToInt(BackupEntry::version).min().orElse(0);
    }
}
