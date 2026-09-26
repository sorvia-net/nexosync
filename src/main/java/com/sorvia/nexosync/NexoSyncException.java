package com.sorvia.nexosync;

import com.sorvia.nexosync.engine.OperationStage;

/**
 * Checked exception carrying the pipeline stage that failed.
 *
 * <p>Every failure surfaced to an operator (console, chat, Discord) is expected to name a stage so
 * that "update failed" is never the only information available.</p>
 */
public class NexoSyncException extends Exception {

    private final OperationStage stage;

    public NexoSyncException(OperationStage stage, String message) {
        super(message);
        this.stage = stage;
    }

    public NexoSyncException(OperationStage stage, String message, Throwable cause) {
        super(message, cause);
        this.stage = stage;
    }

    public OperationStage stage() {
        return stage;
    }
}
