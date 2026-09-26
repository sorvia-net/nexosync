package com.sorvia.nexosync.engine;

import com.sorvia.nexosync.NexoSyncException;
import com.sorvia.nexosync.github.GitHubAsset;
import com.sorvia.nexosync.github.GitHubRelease;
import com.sorvia.nexosync.nexo.NexoBridge;
import com.sorvia.nexosync.notify.DiscordEvent;
import com.sorvia.nexosync.notify.DiscordReport;
import com.sorvia.nexosync.snapshot.SnapshotDiff;
import com.sorvia.nexosync.snapshot.SnapshotManifest;
import com.sorvia.nexosync.util.FileUtils;
import com.sorvia.nexosync.util.Zips;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * The receiver side: checking GitHub, and installing a newer snapshot.
 *
 * <p>The order of operations is the safety guarantee, and it is fixed: download, verify, stage,
 * back up, and only then delete anything live. Every step before the backup leaves the server
 * completely untouched, so a network failure, a corrupt archive or a checksum mismatch can never
 * cost an administrator their Nexo content.</p>
 */
public final class UpdateEngine {

    private final EngineContext context;
    private final Installer installer;
    private final RollbackService rollbacks;

    public UpdateEngine(EngineContext context, RollbackService rollbacks) {
        this.context = context;
        this.installer = new Installer(context);
        this.rollbacks = rollbacks;
    }

    /**
     * Result of a metadata-only check. Nothing is downloaded and nothing is modified.
     *
     * @param localVersion    the snapshot currently installed, 0 when none
     * @param latest          the newest eligible release, when one exists
     * @param updateAvailable whether that release should be installed on this server
     */
    public record CheckResult(int localVersion, Optional<GitHubRelease> latest, boolean updateAvailable) {

        public int remoteVersion() {
            return latest.map(GitHubRelease::snapshotVersion).orElse(0);
        }
    }

    // -----------------------------------------------------------------------------------------
    // Checking
    // -----------------------------------------------------------------------------------------

    /**
     * Queries release metadata only.
     *
     * <p>This is what the scheduled task runs. A snapshot archive is downloaded only once this
     * reports an update, which keeps a one-minute interval cheap for both the server and GitHub.</p>
     */
    public CheckResult check(String operationId) throws NexoSyncException {
        int local = context.state().current().installedSnapshotOrZero();
        Optional<GitHubRelease> latest = context.github().findLatestSnapshot(operationId);

        if (latest.isEmpty()) {
            context.state().recordCheck("no-eligible-release");
            return new CheckResult(local, Optional.empty(), false);
        }

        int remote = latest.get().snapshotVersion();
        boolean available = isInstallable(local, remote);

        context.state().recordCheck(available ? "update-available" : "up-to-date");
        return new CheckResult(local, latest, available);
    }

    private boolean isInstallable(int local, int remote) {
        if (remote == local) {
            return false;
        }
        if (remote > local) {
            return true;
        }
        // remote < local: only a deliberate downgrade may proceed.
        return context.config().update().allowDowngrade() && !context.config().update().requireNewerVersion();
    }

    // -----------------------------------------------------------------------------------------
    // Updating
    // -----------------------------------------------------------------------------------------

    /**
     * Downloads, verifies and installs a release.
     *
     * <p>This method reports rather than throws: an update is an operator-facing operation, and the
     * stage-by-stage result is more useful than an exception. All notifications are emitted here.</p>
     */
    public OperationResult update(String operationId, GitHubRelease release) {
        long started = System.currentTimeMillis();

        int localVersion = context.state().current().installedSnapshotOrZero();
        int targetVersion = release.snapshotVersion();

        OperationResult result = new OperationResult().versions(localVersion, targetVersion);

        Path download = null;
        Path staging = null;
        BackupEntry backup = null;
        boolean liveModified = false;

        context.discord().send(operationId, DiscordEvent.UPDATE_START,
                new DiscordReport("Nexo Snapshot Update Started", DiscordReport.COLOR_INFO)
                        .fieldIf(context.discord().include().serverName(), "Server", context.discord().serverName())
                        .field("Current Snapshot", describeVersion(localVersion))
                        .field("Target Snapshot", "v" + targetVersion));

        try {
            // --- Download -------------------------------------------------------------------
            context.log().progress(operationId, "Starting update to v" + targetVersion + ".");
            GitHubAsset asset = resolveAsset(release);
            download = downloadSnapshot(operationId, release, asset);
            result.stage(OperationStage.DOWNLOAD, OperationResult.StageResult.SUCCESS);

            // --- Manifest -------------------------------------------------------------------
            SnapshotManifest manifest = context.snapshots().readManifest(download);
            if (manifest.snapshotVersion != targetVersion) {
                throw new NexoSyncException(OperationStage.MANIFEST,
                        "Release " + release.tagName() + " carries a manifest for v" + manifest.snapshotVersion + ".");
            }
            context.snapshots().verifyManagedScope(manifest);
            result.stage(OperationStage.MANIFEST, OperationResult.StageResult.SUCCESS);

            // --- Staging --------------------------------------------------------------------
            staging = stageSnapshot(operationId, download, manifest, targetVersion);
            result.stage(OperationStage.STAGING, OperationResult.StageResult.SUCCESS);

            // --- Integrity ------------------------------------------------------------------
            context.log().progress(operationId, "Verifying " + manifest.fileCount + " file(s) against the manifest...");
            context.snapshots().verifyTree(staging, manifest, true, OperationStage.INTEGRITY);
            result.stage(OperationStage.INTEGRITY, OperationResult.StageResult.SUCCESS);

            // --- Backup ---------------------------------------------------------------------
            SnapshotManifest previousManifest = localVersion > 0
                    ? context.state().loadInstalledManifest(localVersion).orElse(null)
                    : null;

            if (context.config().backup().enabled() && context.config().backup().backupBeforeApply()) {
                backup = context.backups().create(operationId, localVersion, previousManifest);
                context.state().recordBackup(backup.id());
                result.stage(OperationStage.BACKUP, OperationResult.StageResult.SUCCESS);
            } else {
                context.log().warn(operationId, "Backups are disabled; this update cannot be rolled back.");
                result.stage(OperationStage.BACKUP, OperationResult.StageResult.SKIPPED);
            }

            // --- Install --------------------------------------------------------------------
            context.state().beginTransaction(targetVersion, localVersion > 0 ? localVersion : null,
                    OperationStage.INSTALLATION.name(), backup == null ? null : backup.id());

            // Stored before installing so that a crash mid-update leaves something to verify against.
            context.state().storeInstalledManifest(manifest);

            liveModified = true;
            installer.removeManagedDirectories(operationId);
            installer.install(operationId, staging, manifest);
            installer.verify(manifest);
            result.stage(OperationStage.INSTALLATION, OperationResult.StageResult.SUCCESS);

            // --- Reload ---------------------------------------------------------------------
            context.state().updateTransactionStage(OperationStage.NEXO_RELOAD.name());
            context.log().progress(operationId, "Reloading Nexo...");

            NexoBridge.ReloadOutcome outcome = context.nexo().reload(operationId);
            if (!outcome.success()) {
                throw new NexoSyncException(OperationStage.NEXO_RELOAD,
                        outcome.errors().isEmpty() ? "Nexo reported a failed reload." : outcome.errorSummary());
            }
            context.log().progress(operationId, "Nexo reload completed via " + outcome.method() + ".");
            result.stage(OperationStage.NEXO_RELOAD, OperationResult.StageResult.SUCCESS);

            // --- Finish ---------------------------------------------------------------------
            context.state().completeTransaction(targetVersion, manifest.snapshotSha256);
            context.state().storeInstalledManifest(manifest);

            SnapshotDiff diff = SnapshotDiff.between(previousManifest, manifest);
            result.diff(diff).duration(System.currentTimeMillis() - started)
                    .succeed("Snapshot v" + targetVersion + " installed.");

            context.backups().prune(operationId);
            context.state().pruneManifests(context.backups().oldestRetainedVersion());

            context.log().info(operationId, "Updated " + describeVersion(localVersion) + " -> v" + targetVersion
                    + " in " + result.durationDisplay() + " (" + diff.summary() + ").");
            reportSuccess(operationId, result, manifest, diff);
            return result;

        } catch (NexoSyncException failure) {
            result.duration(System.currentTimeMillis() - started);
            handleFailure(operationId, result, failure, backup, liveModified, targetVersion);
            return result;

        } catch (RuntimeException unexpected) {
            result.duration(System.currentTimeMillis() - started);
            NexoSyncException wrapped = new NexoSyncException(OperationStage.INSTALLATION,
                    "Unexpected error: " + unexpected, unexpected);
            handleFailure(operationId, result, wrapped, backup, liveModified, targetVersion);
            return result;

        } finally {
            cleanup(operationId, download, staging);
        }
    }

    // -----------------------------------------------------------------------------------------
    // Steps
    // -----------------------------------------------------------------------------------------

    private GitHubAsset resolveAsset(GitHubRelease release) throws NexoSyncException {
        String expected = context.config().github().release().asset().resolveName(release.snapshotVersion());
        Optional<GitHubAsset> asset = release.findSnapshotAsset(expected);

        if (asset.isEmpty()) {
            throw new NexoSyncException(OperationStage.GITHUB_API,
                    "Release " + release.tagName() + " does not contain a snapshot asset named " + expected + ".");
        }
        long limit = context.config().safety().maxPackageSizeBytes();
        if (asset.get().size() > limit) {
            throw new NexoSyncException(OperationStage.INTEGRITY,
                    "The published snapshot is " + FileUtils.humanReadableSize(asset.get().size())
                            + ", which exceeds the configured limit of "
                            + context.config().safety().maxPackageSizeMb() + " MB.");
        }
        return asset.get();
    }

    private Path downloadSnapshot(String operationId, GitHubRelease release, GitHubAsset asset)
            throws NexoSyncException {

        Path target = context.downloadDirectory().resolve(asset.name());
        context.log().progress(operationId, "Downloading " + asset.name() + " ("
                + FileUtils.humanReadableSize(asset.size()) + ") from " + release.tagName() + "...");

        Path downloaded = context.github().downloadAsset(operationId, asset, target);
        context.log().debug(operationId, "Download stored at " + downloaded + ".");
        return downloaded;
    }

    /**
     * Extracts the archive into a version-specific staging directory and checks that the archive
     * contains nothing beyond what the manifest describes.
     */
    private Path stageSnapshot(String operationId, Path archive, SnapshotManifest manifest, int version)
            throws NexoSyncException {

        Path staging = context.stagingDirectory().resolve("v" + version);
        try {
            FileUtils.deleteRecursively(staging);
            FileUtils.ensureDirectory(staging);

            Zips.ExtractionResult extraction = Zips.extract(archive, staging,
                    context.config().safety().maxFileCount(),
                    context.config().safety().maxUncompressedBytes());

            context.log().debug(operationId, "Extracted " + extraction.fileCount() + " entries ("
                    + FileUtils.humanReadableSize(extraction.totalBytes()) + ") into staging.");

            if (context.config().safety().strictManagedPaths()) {
                Set<String> declared = manifest.byPath().keySet();
                for (String entry : extraction.entries()) {
                    if (entry.equals(SnapshotManifest.FILE_NAME)) {
                        continue;
                    }
                    if (!declared.contains(entry)) {
                        throw new NexoSyncException(OperationStage.INTEGRITY,
                                "The archive contains a file the manifest does not describe: " + entry);
                    }
                }
            }
            return staging;

        } catch (IOException stagingFailed) {
            throw new NexoSyncException(OperationStage.STAGING,
                    "Unable to stage the snapshot: " + stagingFailed.getMessage(), stagingFailed);
        }
    }

    // -----------------------------------------------------------------------------------------
    // Failure handling
    // -----------------------------------------------------------------------------------------

    private void handleFailure(String operationId, OperationResult result, NexoSyncException failure,
                               BackupEntry backup, boolean liveModified, int targetVersion) {

        OperationStage stage = failure.stage();
        result.fail(stage, failure.getMessage());
        context.log().error(operationId, "Update to v" + targetVersion + " failed at stage "
                + stage.display() + ": " + failure.getMessage(), failure);

        boolean rolledBack = false;
        boolean rollbackFailed = false;

        if (liveModified && backup != null && shouldRollback(stage)) {
            rolledBack = rollbacks.restoreQuietly(operationId, backup,
                    context.config().rollback().reloadAfterRollback());
            rollbackFailed = !rolledBack;
            result.stage(OperationStage.ROLLBACK, rolledBack
                    ? OperationResult.StageResult.SUCCESS
                    : OperationResult.StageResult.FAILED);
        } else if (liveModified && backup == null) {
            context.log().error(operationId,
                    "The live directories were modified but no backup exists; automatic recovery is not possible.");
        }

        result.rolledBack(rolledBack, rollbackFailed);
        context.state().abortTransaction(stage.display() + ": " + failure.getMessage());

        reportFailure(operationId, result, failure, targetVersion, rolledBack, rollbackFailed);
    }

    private boolean shouldRollback(OperationStage stage) {
        if (!context.config().rollback().enabled()) {
            return false;
        }
        return switch (stage) {
            case NEXO_RELOAD -> context.config().rollback().rollbackOnReloadFailure();
            case INSTALLATION, INTEGRITY -> context.config().rollback().rollbackOnVerificationFailure();
            default -> true;
        };
    }

    // -----------------------------------------------------------------------------------------
    // Reporting
    // -----------------------------------------------------------------------------------------

    private void reportSuccess(String operationId, OperationResult result, SnapshotManifest manifest,
                               SnapshotDiff diff) {

        var include = context.discord().include();
        DiscordReport report = new DiscordReport("Nexo Snapshot Updated", DiscordReport.COLOR_SUCCESS)
                .fieldIf(include.serverName(), "Server", context.discord().serverName())
                .fieldIf(include.snapshotVersion(), "Previous Snapshot",
                        describeVersion(result.fromVersion().orElse(0)))
                .fieldIf(include.snapshotVersion(), "New Snapshot", "v" + manifest.snapshotVersion)
                .fieldIf(include.fileCount(), "Files", Integer.toString(manifest.fileCount))
                .fieldIf(include.duration(), "Duration", result.durationDisplay())
                .field("Integrity", "SUCCESS")
                .field("Nexo Reload", "SUCCESS");

        if (include.changedFiles() && !diff.isEmpty()) {
            report.blockIf(true, "Changes", diff.summary() + "\n```\n"
                    + String.join("\n", diff.preview(15)) + "\n```");
        }
        context.discord().send(operationId, DiscordEvent.UPDATE_SUCCESS, report);
    }

    private void reportFailure(String operationId, OperationResult result, NexoSyncException failure,
                               int targetVersion, boolean rolledBack, boolean rollbackFailed) {

        var include = context.discord().include();
        boolean critical = rollbackFailed;

        DiscordReport report = new DiscordReport(
                critical ? "Nexo Snapshot Update FAILED - ROLLBACK FAILED" : "Nexo Snapshot Update Failed",
                critical ? DiscordReport.COLOR_CRITICAL : DiscordReport.COLOR_FAILURE)
                .fieldIf(include.serverName(), "Server", context.discord().serverName())
                .fieldIf(include.snapshotVersion(), "Target Snapshot", "v" + targetVersion)
                .field("Failed Stage", failure.stage().display())
                .fieldIf(include.duration(), "Duration", result.durationDisplay())
                .blockIf(true, "Stages", "```\n" + result.stageTable() + "\n```")
                .field("Rollback", rolledBack ? "SUCCESS" : (rollbackFailed ? "FAILED" : "NOT REQUIRED"));

        if (include.errorDetails()) {
            report.blockIf(true, "Reason", "```\n" + failure.getMessage() + "\n```");
        }
        if (critical) {
            report.description("Manual intervention is required. NexoSync has stopped modifying this server.");
        }

        DiscordEvent event = switch (failure.stage()) {
            case INTEGRITY, MANIFEST, ARCHIVE -> DiscordEvent.INTEGRITY_FAILURE;
            case GITHUB_API, GITHUB_AUTH, DOWNLOAD -> DiscordEvent.GITHUB_FAILURE;
            default -> DiscordEvent.UPDATE_FAILURE;
        };
        context.discord().send(operationId, event, report);
    }

    // -----------------------------------------------------------------------------------------
    // Housekeeping
    // -----------------------------------------------------------------------------------------

    private void cleanup(String operationId, Path download, Path staging) {
        try {
            if (staging != null) {
                FileUtils.deleteRecursively(staging);
            }
            if (download != null) {
                Files.deleteIfExists(download);
            }
        } catch (IOException cleanupFailed) {
            context.log().debug(operationId, "Unable to clean temporary files: " + cleanupFailed.getMessage());
        }
    }

    private static String describeVersion(int version) {
        return version > 0 ? "v" + version : "none";
    }
}
