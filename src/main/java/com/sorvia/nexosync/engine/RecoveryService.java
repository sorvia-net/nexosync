package com.sorvia.nexosync.engine;

import com.sorvia.nexosync.NexoSyncException;
import com.sorvia.nexosync.notify.DiscordEvent;
import com.sorvia.nexosync.notify.DiscordReport;
import com.sorvia.nexosync.snapshot.SnapshotManifest;
import com.sorvia.nexosync.state.NexoSyncState;

import java.util.Optional;

/**
 * Recovers from an update that was interrupted by a crash or a restart.
 *
 * <p>A server that stops mid-update leaves a pending transaction in {@code state.json}. Assuming the
 * update succeeded would be the dangerous choice: the managed directories might hold half of one
 * snapshot and half of another. Instead the live tree is verified against the manifests NexoSync
 * stored, and only a tree that actually matches one of them is accepted.</p>
 */
public final class RecoveryService {

    private final EngineContext context;
    private final RollbackService rollbacks;

    public RecoveryService(EngineContext context, RollbackService rollbacks) {
        this.context = context;
        this.rollbacks = rollbacks;
    }

    /**
     * Outcome of a recovery attempt, for logging and reporting.
     */
    public enum Outcome {
        /** No interrupted update was recorded. */
        NOT_NEEDED,
        /** The new snapshot was fully installed before the server stopped. */
        COMPLETED,
        /** The previous snapshot was still intact; the update simply never happened. */
        UNCHANGED,
        /** The managed directories were inconsistent and a backup was restored. */
        RESTORED,
        /** The managed directories were inconsistent and could not be repaired. */
        FAILED
    }

    public Outcome recover(String operationId) {
        NexoSyncState state = context.state().current();
        NexoSyncState.PendingTransaction pending = state.pendingTransaction;

        if (pending == null) {
            return Outcome.NOT_NEEDED;
        }

        context.log().warn(operationId, "An interrupted update was detected: " + pending
                + ". Validating the managed directories before continuing.");

        Optional<SnapshotManifest> target = pending.targetVersion == null
                ? Optional.empty()
                : context.state().loadInstalledManifest(pending.targetVersion);
        Optional<SnapshotManifest> previous = pending.previousVersion == null
                ? Optional.empty()
                : context.state().loadInstalledManifest(pending.previousVersion);

        if (target.isPresent() && matchesLiveTree(target.get())) {
            context.state().completeTransaction(target.get().snapshotVersion, target.get().snapshotSha256);
            context.log().info(operationId, "The interrupted update had already completed; snapshot v"
                    + target.get().snapshotVersion + " is installed and intact.");
            return Outcome.COMPLETED;
        }

        if (previous.isPresent() && matchesLiveTree(previous.get())) {
            context.state().mutate(recovered -> {
                recovered.installedSnapshot = previous.get().snapshotVersion;
                recovered.snapshotSha256 = previous.get().snapshotSha256;
                recovered.pendingTransaction = null;
                recovered.lastError = "Update to v" + pending.targetVersion + " was interrupted and did not apply.";
            });
            context.log().info(operationId, "The interrupted update never modified the managed directories; "
                    + "snapshot v" + previous.get().snapshotVersion + " remains installed.");
            return Outcome.UNCHANGED;
        }

        return restoreFromBackup(operationId, pending);
    }

    private Outcome restoreFromBackup(String operationId, NexoSyncState.PendingTransaction pending) {
        context.log().error(operationId,
                "The managed Nexo directories do not match any known snapshot. Restoring the most recent backup.");

        Optional<BackupEntry> backup = pending.backupId == null
                ? context.backups().latest()
                : context.backups().findById(pending.backupId).or(() -> context.backups().latest());

        if (backup.isEmpty()) {
            context.log().error(operationId,
                    "No backup is available. NexoSync will not modify the Nexo directory further; "
                            + "restore the content manually before publishing or updating again.");
            context.state().abortTransaction("Interrupted update could not be recovered: no backup available.");
            notifyCritical(operationId, pending, "No backup was available to restore.");
            return Outcome.FAILED;
        }

        boolean restored = rollbacks.restoreQuietly(operationId, backup.get(), true);
        if (restored) {
            context.log().info(operationId, "Recovered by restoring backup " + backup.get().id() + ".");
            context.discord().send(operationId, DiscordEvent.ROLLBACK_SUCCESS,
                    new DiscordReport("NexoSync Recovered An Interrupted Update", DiscordReport.COLOR_WARNING)
                            .fieldIf(context.discord().include().serverName(), "Server",
                                    context.discord().serverName())
                            .field("Interrupted Target", "v" + pending.targetVersion)
                            .field("Restored Backup", backup.get().id()));
            return Outcome.RESTORED;
        }

        context.state().abortTransaction("Interrupted update could not be recovered: restore failed.");
        notifyCritical(operationId, pending, "Restoring backup " + backup.get().id() + " failed.");
        return Outcome.FAILED;
    }

    private boolean matchesLiveTree(SnapshotManifest manifest) {
        try {
            context.snapshots().verifyTree(context.nexoDirectory(), manifest,
                    context.config().safety().strictManagedPaths(), OperationStage.INSTALLATION);
            return true;
        } catch (NexoSyncException mismatch) {
            context.log().debug(null, "Live tree does not match v" + manifest.snapshotVersion
                    + ": " + mismatch.getMessage());
            return false;
        }
    }

    private void notifyCritical(String operationId, NexoSyncState.PendingTransaction pending, String reason) {
        context.discord().send(operationId, DiscordEvent.ROLLBACK_FAILURE,
                new DiscordReport("NexoSync Could Not Recover An Interrupted Update", DiscordReport.COLOR_CRITICAL)
                        .description("The managed Nexo directories are in an unknown state and require manual review.")
                        .fieldIf(context.discord().include().serverName(), "Server", context.discord().serverName())
                        .field("Interrupted Target", "v" + pending.targetVersion)
                        .blockIf(context.discord().include().errorDetails(), "Reason", reason));
    }
}
