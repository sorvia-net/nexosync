package com.sorvia.nexosync.state;

import com.google.gson.annotations.SerializedName;

/**
 * The locally persisted synchronization state.
 *
 * <p>This document answers two questions after a restart: which snapshot is installed, and was an
 * update in progress when the server stopped. The second one is what makes an interrupted update
 * recoverable instead of silently assumed successful.</p>
 */
public final class NexoSyncState {

    @SerializedName("installed_snapshot")
    public Integer installedSnapshot;

    @SerializedName("snapshot_sha256")
    public String snapshotSha256;

    @SerializedName("last_successful_update")
    public String lastSuccessfulUpdate;

    @SerializedName("last_check")
    public String lastCheck;

    @SerializedName("last_check_result")
    public String lastCheckResult;

    @SerializedName("last_error")
    public String lastError;

    @SerializedName("last_backup")
    public String lastBackup;

    @SerializedName("pending_transaction")
    public PendingTransaction pendingTransaction;

    public boolean hasInstalledSnapshot() {
        return installedSnapshot != null && installedSnapshot > 0;
    }

    public int installedSnapshotOrZero() {
        return hasInstalledSnapshot() ? installedSnapshot : 0;
    }

    /**
     * An update that started but has not been confirmed complete.
     */
    public static final class PendingTransaction {

        @SerializedName("target_version")
        public Integer targetVersion;

        @SerializedName("previous_version")
        public Integer previousVersion;

        public String stage;

        @SerializedName("started_at")
        public String startedAt;

        @SerializedName("backup_id")
        public String backupId;

        public PendingTransaction() {
            // Required by the JSON codec.
        }

        public PendingTransaction(Integer targetVersion, Integer previousVersion, String stage, String startedAt,
                                  String backupId) {
            this.targetVersion = targetVersion;
            this.previousVersion = previousVersion;
            this.stage = stage;
            this.startedAt = startedAt;
            this.backupId = backupId;
        }

        @Override
        public String toString() {
            return "v" + targetVersion + " (stage " + stage + ", started " + startedAt + ")";
        }
    }
}
