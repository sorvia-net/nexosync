package com.sorvia.nexosync.engine;

import com.sorvia.nexosync.snapshot.SnapshotDiff;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Outcome of a push, update or rollback.
 *
 * <p>The per-stage record is what turns "update failed" into a report an administrator can act on:
 * it shows how far the operation got before something went wrong.</p>
 */
public final class OperationResult {

    /**
     * Result of a single stage.
     */
    public enum StageResult {
        SUCCESS,
        FAILED,
        SKIPPED
    }

    private final Map<OperationStage, StageResult> stages = new LinkedHashMap<>();

    private boolean success;
    private OperationStage failedStage;
    private String message;
    private long durationMillis;
    private Integer fromVersion;
    private Integer toVersion;
    private SnapshotDiff diff;
    private boolean rolledBack;
    private boolean rollbackFailed;

    public OperationResult stage(OperationStage stage, StageResult result) {
        stages.put(stage, result);
        return this;
    }

    public OperationResult succeed(String message) {
        this.success = true;
        this.message = message;
        return this;
    }

    public OperationResult fail(OperationStage stage, String message) {
        this.success = false;
        this.failedStage = stage;
        this.message = message;
        stages.put(stage, StageResult.FAILED);
        return this;
    }

    public OperationResult duration(long millis) {
        this.durationMillis = millis;
        return this;
    }

    public OperationResult versions(Integer from, Integer to) {
        this.fromVersion = from;
        this.toVersion = to;
        return this;
    }

    public OperationResult diff(SnapshotDiff diff) {
        this.diff = diff;
        return this;
    }

    public OperationResult rolledBack(boolean rolledBack, boolean rollbackFailed) {
        this.rolledBack = rolledBack;
        this.rollbackFailed = rollbackFailed;
        return this;
    }

    public boolean isSuccess() {
        return success;
    }

    public Optional<OperationStage> failedStage() {
        return Optional.ofNullable(failedStage);
    }

    public String message() {
        return message == null ? "" : message;
    }

    public long durationMillis() {
        return durationMillis;
    }

    public String durationDisplay() {
        return String.format(java.util.Locale.ROOT, "%.2fs", durationMillis / 1000.0);
    }

    public Optional<Integer> fromVersion() {
        return Optional.ofNullable(fromVersion);
    }

    public Optional<Integer> toVersion() {
        return Optional.ofNullable(toVersion);
    }

    public Optional<SnapshotDiff> diff() {
        return Optional.ofNullable(diff);
    }

    public boolean wasRolledBack() {
        return rolledBack;
    }

    public boolean rollbackFailed() {
        return rollbackFailed;
    }

    public Map<OperationStage, StageResult> stages() {
        return Map.copyOf(stages);
    }

    /**
     * Renders the stage table used in console output and Discord reports.
     */
    public String stageTable() {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<OperationStage, StageResult> entry : stages.entrySet()) {
            builder.append(String.format(java.util.Locale.ROOT, "%-14s %s%n",
                    entry.getKey().display(), entry.getValue().name()));
        }
        return builder.toString().stripTrailing();
    }
}
