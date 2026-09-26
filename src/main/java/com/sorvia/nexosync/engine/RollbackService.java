package com.sorvia.nexosync.engine;

import com.sorvia.nexosync.NexoSyncException;
import com.sorvia.nexosync.nexo.NexoBridge;
import com.sorvia.nexosync.notify.DiscordEvent;
import com.sorvia.nexosync.notify.DiscordReport;
import com.sorvia.nexosync.snapshot.SnapshotManifest;

import java.util.Optional;

/**
 * Restores a previously backed up snapshot.
 *
 * <p>Rollback runs in two situations: automatically when an update fails after the live directories
 * were touched, and manually through {@code /nexosync rollback}. Both paths share the same restore
 * logic so that an automatic recovery is never less careful than a manual one.</p>
 */
public final class RollbackService {

    private final EngineContext context;

    public RollbackService(EngineContext context) {
        this.context = context;
    }

    /**
     * Resolves which backup a rollback request refers to.
     *
     * @param version the snapshot version to restore, or null for the most recent backup
     */
    public Optional<BackupEntry> resolveTarget(Integer version) {
        return version == null
                ? context.backups().latest()
                : context.backups().findByVersion(version);
    }

    /**
     * Restores a backup without producing a user-facing report.
     *
     * <p>Used by the update engine when an installation has to be undone. Failures are logged and
     * reported through the return value rather than thrown, because the caller is already handling
     * an error and needs to finish its own reporting.</p>
     *
     * @return true when the previous state is back in place
     */
    public boolean restoreQuietly(String operationId, BackupEntry entry, boolean reloadAfterwards) {
        context.log().warn(operationId, "Rolling back to " + entry.display() + "...");

        try {
            context.backups().restore(operationId, entry, context.workDirectory());
        } catch (NexoSyncException restoreFailed) {
            context.log().error(operationId, "Rollback failed: " + restoreFailed.getMessage(), restoreFailed);
            return false;
        }

        Optional<SnapshotManifest> manifest = context.backups().readManifest(entry);
        context.state().mutate(state -> {
            state.installedSnapshot = entry.version() > 0 ? entry.version() : null;
            state.snapshotSha256 = manifest.map(value -> value.snapshotSha256).orElse(null);
            state.pendingTransaction = null;
        });
        manifest.ifPresent(value -> context.state().storeInstalledManifest(value));

        if (!reloadAfterwards) {
            return true;
        }

        try {
            NexoBridge.ReloadOutcome outcome = context.nexo().reload(operationId);
            if (!outcome.success()) {
                context.log().error(operationId,
                        "Nexo reload after rollback reported errors: " + outcome.errorSummary());
                return false;
            }
            return true;
        } catch (NexoSyncException reloadFailed) {
            context.log().error(operationId, "Nexo reload after rollback failed: " + reloadFailed.getMessage(),
                    reloadFailed);
            return false;
        }
    }

    /**
     * Performs an operator-requested rollback, including Discord reporting.
     */
    public OperationResult rollback(String operationId, BackupEntry entry) {
        long started = System.currentTimeMillis();
        OperationResult result = new OperationResult()
                .versions(context.state().current().installedSnapshot, entry.version());

        context.discord().send(operationId, DiscordEvent.ROLLBACK_START,
                new DiscordReport("Nexo Snapshot Rollback Started", DiscordReport.COLOR_WARNING)
                        .fieldIf(context.discord().include().serverName(), "Server", context.discord().serverName())
                        .field("Target Snapshot", "v" + entry.version())
                        .field("Backup", entry.id()));

        boolean restored = restoreQuietly(operationId, entry, context.config().rollback().reloadAfterRollback());
        result.stage(OperationStage.ROLLBACK, restored
                ? OperationResult.StageResult.SUCCESS
                : OperationResult.StageResult.FAILED);
        result.duration(System.currentTimeMillis() - started);

        if (restored) {
            result.succeed("Rollback to v" + entry.version() + " completed.");
            context.discord().send(operationId, DiscordEvent.ROLLBACK_SUCCESS,
                    new DiscordReport("Nexo Snapshot Rolled Back", DiscordReport.COLOR_SUCCESS)
                            .fieldIf(context.discord().include().serverName(), "Server", context.discord().serverName())
                            .fieldIf(context.discord().include().snapshotVersion(), "Restored Snapshot", "v" + entry.version())
                            .field("Backup", entry.id())
                            .fieldIf(context.discord().include().duration(), "Duration", result.durationDisplay()));
        } else {
            result.fail(OperationStage.ROLLBACK, "Rollback to v" + entry.version() + " failed.");
            context.state().recordError("Rollback to v" + entry.version() + " failed.");
            context.discord().send(operationId, DiscordEvent.ROLLBACK_FAILURE,
                    new DiscordReport("Nexo Snapshot Rollback FAILED", DiscordReport.COLOR_CRITICAL)
                            .description("Manual intervention is required. The backup has been preserved.")
                            .fieldIf(context.discord().include().serverName(), "Server", context.discord().serverName())
                            .field("Target Snapshot", "v" + entry.version())
                            .field("Backup", entry.id()));
        }
        return result;
    }
}
