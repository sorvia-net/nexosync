package com.sorvia.nexosync.state;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sorvia.nexosync.log.NexoSyncLogger;
import com.sorvia.nexosync.snapshot.SnapshotManifest;
import com.sorvia.nexosync.util.FileUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Reads and writes {@code state.json} and the installed-manifest archive beside it.
 *
 * <p>Every write is atomic. A crash in the middle of an update must leave either the previous
 * document or the new one, never a truncated file that would make the plugin refuse to start.</p>
 */
public final class StateStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path stateFile;
    private final Path manifestDirectory;
    private final NexoSyncLogger log;
    private final Object lock = new Object();

    private NexoSyncState state = new NexoSyncState();

    public StateStore(Path dataFolder, NexoSyncLogger log) {
        this.stateFile = dataFolder.resolve("state.json");
        this.manifestDirectory = dataFolder.resolve("cache").resolve("manifests");
        this.log = log;
    }

    public void load() {
        synchronized (lock) {
            if (!Files.isRegularFile(stateFile)) {
                state = new NexoSyncState();
                return;
            }
            try {
                String json = Files.readString(stateFile, StandardCharsets.UTF_8);
                NexoSyncState loaded = GSON.fromJson(json, NexoSyncState.class);
                state = loaded == null ? new NexoSyncState() : loaded;
            } catch (IOException | RuntimeException unreadable) {
                log.warn(null, "state.json could not be read and was reset: " + unreadable.getMessage());
                state = new NexoSyncState();
            }
        }
    }

    /**
     * Returns a snapshot of the current state. The returned object is a copy, so callers cannot
     * mutate persisted state by accident.
     */
    public NexoSyncState current() {
        synchronized (lock) {
            NexoSyncState copy = GSON.fromJson(GSON.toJson(state), NexoSyncState.class);
            return copy == null ? new NexoSyncState() : copy;
        }
    }

    /**
     * Applies a change and persists it immediately.
     */
    public void mutate(Consumer<NexoSyncState> change) {
        synchronized (lock) {
            change.accept(state);
            persist();
        }
    }

    private void persist() {
        try {
            FileUtils.writeAtomic(stateFile, GSON.toJson(state));
        } catch (IOException writeFailed) {
            log.error(null, "Unable to persist state.json", writeFailed);
        }
    }

    // -----------------------------------------------------------------------------------------
    // Transactions
    // -----------------------------------------------------------------------------------------

    public void beginTransaction(int targetVersion, Integer previousVersion, String stage, String backupId) {
        mutate(state -> state.pendingTransaction = new NexoSyncState.PendingTransaction(
                targetVersion, previousVersion, stage, Instant.now().toString(), backupId));
    }

    public void updateTransactionStage(String stage) {
        mutate(state -> {
            if (state.pendingTransaction != null) {
                state.pendingTransaction.stage = stage;
            }
        });
    }

    public void completeTransaction(int installedVersion, String snapshotHash) {
        mutate(state -> {
            state.installedSnapshot = installedVersion;
            state.snapshotSha256 = snapshotHash;
            state.lastSuccessfulUpdate = Instant.now().toString();
            state.lastError = null;
            state.pendingTransaction = null;
        });
    }

    public void abortTransaction(String error) {
        mutate(state -> {
            state.lastError = error;
            state.pendingTransaction = null;
        });
    }

    public void recordCheck(String result) {
        mutate(state -> {
            state.lastCheck = Instant.now().toString();
            state.lastCheckResult = result;
        });
    }

    public void recordBackup(String backupId) {
        mutate(state -> state.lastBackup = backupId);
    }

    public void recordError(String error) {
        mutate(state -> state.lastError = error);
    }

    // -----------------------------------------------------------------------------------------
    // Installed manifests
    // -----------------------------------------------------------------------------------------

    /**
     * Stores the manifest of a snapshot that is now live.
     *
     * <p>Keeping it locally is what allows the plugin to verify the managed directories after a
     * crash, and to report a meaningful file-level diff on the next update.</p>
     */
    public void storeInstalledManifest(SnapshotManifest manifest) {
        try {
            FileUtils.ensureDirectory(manifestDirectory);
            FileUtils.writeAtomic(manifestDirectory.resolve("v" + manifest.snapshotVersion + ".json"),
                    manifest.toJsonBytes());
        } catch (IOException writeFailed) {
            log.warn(null, "Unable to store the installed manifest: " + writeFailed.getMessage());
        }
    }

    public Optional<SnapshotManifest> loadInstalledManifest(int version) {
        Path file = manifestDirectory.resolve("v" + version + ".json");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(SnapshotManifest.fromJson(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException unreadable) {
            log.warn(null, "Stored manifest for v" + version + " is unreadable: " + unreadable.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Drops stored manifests for snapshots that are no longer reachable through a backup.
     */
    public void pruneManifests(int keepAtLeastVersion) {
        if (!Files.isDirectory(manifestDirectory)) {
            return;
        }
        try (var stream = Files.list(manifestDirectory)) {
            for (Path file : stream.toList()) {
                String name = file.getFileName().toString();
                if (!name.startsWith("v") || !name.endsWith(".json")) {
                    continue;
                }
                try {
                    int version = Integer.parseInt(name.substring(1, name.length() - 5));
                    if (version < keepAtLeastVersion) {
                        Files.deleteIfExists(file);
                    }
                } catch (NumberFormatException notAVersion) {
                    // Leave files that do not follow the naming scheme alone.
                }
            }
        } catch (IOException pruneFailed) {
            log.debug(null, "Unable to prune stored manifests: " + pruneFailed.getMessage());
        }
    }
}
