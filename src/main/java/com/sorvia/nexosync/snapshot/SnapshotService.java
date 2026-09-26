package com.sorvia.nexosync.snapshot;

import com.sorvia.nexosync.NexoSyncException;
import com.sorvia.nexosync.config.NexoSyncConfig;
import com.sorvia.nexosync.engine.OperationStage;
import com.sorvia.nexosync.log.NexoSyncLogger;
import com.sorvia.nexosync.util.FileUtils;
import com.sorvia.nexosync.util.Hashing;
import com.sorvia.nexosync.util.Zips;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds, packages and verifies snapshots.
 *
 * <p>This class is the single place that decides what "the synchronized content" means. Everything
 * else - publishing, installing, backing up - works from the manifest it produces.</p>
 */
public final class SnapshotService {

    private final NexoSyncConfig config;
    private final Path nexoDirectory;
    private final NexoSyncLogger log;

    public SnapshotService(NexoSyncConfig config, Path nexoDirectory, NexoSyncLogger log) {
        this.config = config;
        this.nexoDirectory = nexoDirectory;
        this.log = log;
    }

    public Path nexoDirectory() {
        return nexoDirectory;
    }

    public List<String> managedPaths() {
        return config.sync().paths();
    }

    /**
     * Result of scanning the live Nexo directory.
     *
     * @param files       managed relative path to the file it was read from
     * @param entries     manifest entries in path order
     * @param totalBytes  sum of all file sizes
     * @param snapshotHash content identity of the whole set
     */
    public record Collected(Map<String, Path> files, List<ManifestEntry> entries, long totalBytes, String snapshotHash) {

        public int fileCount() {
            return entries.size();
        }
    }

    /**
     * Metadata that describes where a snapshot came from. None of it participates in the content
     * hash; it exists so an operator can tell which server published a release.
     */
    public record Metadata(String publisher, String nexosyncVersion, String nexoVersion, String serverVersion, String platform) {
    }

    // -----------------------------------------------------------------------------------------
    // Collecting
    // -----------------------------------------------------------------------------------------

    /**
     * Scans every configured managed directory and hashes its contents.
     *
     * <p>A managed directory that does not exist is treated as empty rather than as an error: a
     * server may legitimately have no external packs yet.</p>
     */
    public Collected collect(String operationId) throws NexoSyncException {
        if (!Files.isDirectory(nexoDirectory)) {
            throw new NexoSyncException(OperationStage.CONFIGURATION,
                    "The configured Nexo directory does not exist: " + nexoDirectory);
        }

        Map<String, Path> files = new TreeMap<>();
        List<ManifestEntry> entries = new ArrayList<>();
        long totalBytes = 0L;

        for (String managed : config.sync().paths()) {
            Path root = nexoDirectory.resolve(managed).normalize();

            if (!FileUtils.isWithin(nexoDirectory, root)) {
                throw new NexoSyncException(OperationStage.CONFIGURATION,
                        "Configured sync path escapes the Nexo directory: " + managed);
            }
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                log.debug(operationId, "Managed path " + managed + " does not exist yet, treating it as empty.");
                continue;
            }
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new NexoSyncException(OperationStage.CONFIGURATION,
                        "Configured sync path is not a directory: " + managed);
            }

            List<String> relative;
            try {
                relative = FileUtils.listRelativeFiles(root, config.safety().rejectSymbolicLinks());
            } catch (IOException scanFailed) {
                throw new NexoSyncException(OperationStage.CONFIGURATION,
                        "Unable to read managed path " + managed + ": " + scanFailed.getMessage(), scanFailed);
            }

            for (String relativePath : relative) {
                String snapshotPath = managed + "/" + relativePath;
                Path file = root.resolve(relativePath);

                try {
                    long size = Files.size(file);
                    entries.add(new ManifestEntry(snapshotPath, size, Hashing.sha256(file)));
                    files.put(snapshotPath, file);
                    totalBytes += size;
                } catch (IOException hashFailed) {
                    throw new NexoSyncException(OperationStage.CONFIGURATION,
                            "Unable to read " + snapshotPath + ": " + hashFailed.getMessage(), hashFailed);
                }

                if (entries.size() > config.safety().maxFileCount()) {
                    throw new NexoSyncException(OperationStage.CONFIGURATION,
                            "The snapshot would contain more than " + config.safety().maxFileCount() + " files.");
                }
            }
        }

        if (entries.isEmpty()) {
            throw new NexoSyncException(OperationStage.CONFIGURATION,
                    "No files found in the configured sync paths; there is nothing to publish.");
        }

        String snapshotHash = SnapshotManifest.computeSnapshotHash(entries);
        log.debug(operationId, "Collected " + entries.size() + " files (" + FileUtils.humanReadableSize(totalBytes)
                + "), snapshot hash " + snapshotHash);

        return new Collected(Map.copyOf(files), List.copyOf(entries), totalBytes, snapshotHash);
    }

    public SnapshotManifest buildManifest(int version, Collected collected, Metadata metadata) {
        SnapshotManifest manifest = new SnapshotManifest();
        manifest.schemaVersion = SnapshotManifest.CURRENT_SCHEMA_VERSION;
        manifest.snapshotVersion = version;
        manifest.generatedAt = Instant.now().toString();
        manifest.publisher = metadata.publisher();
        manifest.nexosyncVersion = metadata.nexosyncVersion();
        manifest.nexoVersion = metadata.nexoVersion();
        manifest.serverVersion = metadata.serverVersion();
        manifest.platform = metadata.platform();
        manifest.managedPaths = new ArrayList<>(config.sync().paths());
        manifest.files = new ArrayList<>(collected.entries());
        manifest.fileCount = collected.entries().size();
        manifest.snapshotSha256 = collected.snapshotHash();
        return manifest;
    }

    // -----------------------------------------------------------------------------------------
    // Packaging
    // -----------------------------------------------------------------------------------------

    /**
     * Writes the snapshot archive. The manifest is added last, under its well-known name.
     */
    public Path packageSnapshot(Path workDirectory, String assetName, Collected collected, SnapshotManifest manifest)
            throws NexoSyncException {

        try {
            FileUtils.ensureDirectory(workDirectory);
            Path manifestFile = Zips.writeTemporary(workDirectory, SnapshotManifest.FILE_NAME, manifest.toJsonBytes());

            Map<String, Path> entries = new LinkedHashMap<>(collected.files());
            entries.put(SnapshotManifest.FILE_NAME, manifestFile);

            Path archive = workDirectory.resolve(assetName);
            Zips.create(archive, entries);

            long size = Files.size(archive);
            if (size > config.safety().maxPackageSizeBytes()) {
                Files.deleteIfExists(archive);
                throw new NexoSyncException(OperationStage.CONFIGURATION,
                        "The generated snapshot is " + FileUtils.humanReadableSize(size)
                                + ", which exceeds the configured limit of " + config.safety().maxPackageSizeMb() + " MB.");
            }
            return archive;
        } catch (IOException packagingFailed) {
            throw new NexoSyncException(OperationStage.ARCHIVE,
                    "Unable to create the snapshot package: " + packagingFailed.getMessage(), packagingFailed);
        }
    }

    // -----------------------------------------------------------------------------------------
    // Verification
    // -----------------------------------------------------------------------------------------

    /**
     * Reads and structurally validates the manifest carried by an archive.
     */
    public SnapshotManifest readManifest(Path archive) throws NexoSyncException {
        byte[] document;
        try {
            document = Zips.readEntry(archive, SnapshotManifest.FILE_NAME, 32L * 1024 * 1024);
        } catch (IOException readFailed) {
            throw new NexoSyncException(OperationStage.ARCHIVE,
                    "The downloaded snapshot is not a readable archive: " + readFailed.getMessage(), readFailed);
        }
        if (document == null) {
            throw new NexoSyncException(OperationStage.MANIFEST, "The snapshot does not contain " + SnapshotManifest.FILE_NAME + ".");
        }

        SnapshotManifest manifest;
        try {
            manifest = SnapshotManifest.fromJson(document);
        } catch (RuntimeException malformed) {
            throw new NexoSyncException(OperationStage.MANIFEST,
                    "The snapshot manifest is not valid JSON: " + malformed.getMessage(), malformed);
        }

        List<String> problems = manifest.problems();
        if (!problems.isEmpty()) {
            throw new NexoSyncException(OperationStage.MANIFEST,
                    "The snapshot manifest is invalid: " + String.join("; ", problems));
        }
        return manifest;
    }

    /**
     * Confirms that a manifest only claims paths this server is configured to manage.
     *
     * <p>A publisher that synchronises more directories than a receiver does would otherwise be able
     * to write anywhere inside the Nexo directory. With {@code safety.strict-managed-paths} enabled
     * the mismatch aborts the update instead.</p>
     */
    public void verifyManagedScope(SnapshotManifest manifest) throws NexoSyncException {
        Set<String> local = new LinkedHashSet<>(config.sync().paths());
        List<String> foreign = new ArrayList<>();

        for (String declared : manifest.managedPaths) {
            if (!local.contains(declared)) {
                foreign.add(declared);
            }
        }

        for (ManifestEntry entry : manifest.files) {
            if (!FileUtils.isSafeRelativePath(entry.path)) {
                throw new NexoSyncException(OperationStage.INTEGRITY,
                        "The snapshot manifest contains an unsafe path: " + entry.path);
            }
            if (!isInsideManagedPaths(entry.path, local)) {
                if (config.safety().strictManagedPaths()) {
                    throw new NexoSyncException(OperationStage.INTEGRITY,
                            "The snapshot contains " + entry.path + ", which is outside this server's configured sync paths.");
                }
                foreign.add(entry.path);
            }
        }

        if (!foreign.isEmpty()) {
            log.warn(null, "Snapshot declares content outside this server's sync paths: " + String.join(", ", foreign));
        }

        // A managed directory the snapshot says nothing about is still emptied, because a managed
        // directory is replaced rather than merged. That is easy to configure by accident, so it is
        // called out explicitly rather than discovered after the fact.
        List<String> unmanagedLocally = new ArrayList<>();
        for (String configured : config.sync().paths()) {
            if (!manifest.managedPaths.contains(configured)) {
                unmanagedLocally.add(configured);
            }
        }
        if (!unmanagedLocally.isEmpty()) {
            log.warn(null, "The snapshot does not cover " + String.join(", ", unmanagedLocally)
                    + "; these directories are configured locally and will be emptied by this update. "
                    + "Remove them from sync.paths if that is not intended.");
        }
    }

    private static boolean isInsideManagedPaths(String path, Set<String> managedPaths) {
        for (String managed : managedPaths) {
            if (path.equals(managed) || path.startsWith(managed + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Verifies a directory tree against a manifest.
     *
     * @param root   directory whose layout mirrors the Nexo directory (staging area or live tree)
     * @param strict when true, a file that exists inside a managed path but is absent from the
     *               manifest is an error - this is what proves an install actually replaced content
     * @param stage  stage reported when verification fails
     */
    public void verifyTree(Path root, SnapshotManifest manifest, boolean strict, OperationStage stage)
            throws NexoSyncException {

        for (ManifestEntry entry : manifest.files) {
            Path file = root.resolve(entry.path).normalize();

            if (!FileUtils.isWithin(root, file)) {
                throw new NexoSyncException(OperationStage.INTEGRITY,
                        "Manifest entry resolves outside the verified directory: " + entry.path);
            }
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new NexoSyncException(stage, "Expected file is missing: " + entry.path);
            }

            try {
                long size = Files.size(file);
                if (size != entry.size) {
                    throw new NexoSyncException(stage,
                            "Size mismatch for " + entry.path + " (expected " + entry.size + ", found " + size + ").");
                }
                String actual = Hashing.sha256(file);
                if (!actual.equalsIgnoreCase(entry.sha256)) {
                    throw new NexoSyncException(OperationStage.INTEGRITY,
                            "Checksum mismatch for " + entry.path + ".");
                }
            } catch (IOException readFailed) {
                throw new NexoSyncException(stage,
                        "Unable to verify " + entry.path + ": " + readFailed.getMessage(), readFailed);
            }
        }

        if (!strict) {
            return;
        }

        Set<String> expected = manifest.byPath().keySet();
        for (String managed : manifest.managedPaths) {
            Path managedRoot = root.resolve(managed).normalize();
            if (!Files.isDirectory(managedRoot, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            List<String> present;
            try {
                present = FileUtils.listRelativeFiles(managedRoot, config.safety().rejectSymbolicLinks());
            } catch (IOException scanFailed) {
                throw new NexoSyncException(stage,
                        "Unable to list " + managed + " while verifying: " + scanFailed.getMessage(), scanFailed);
            }
            for (String relativePath : present) {
                String full = managed + "/" + relativePath;
                if (!expected.contains(full)) {
                    throw new NexoSyncException(stage,
                            "Unexpected file remained inside a managed directory: " + full);
                }
            }
        }
    }
}
