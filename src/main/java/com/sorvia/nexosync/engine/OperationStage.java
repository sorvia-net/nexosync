package com.sorvia.nexosync.engine;

/**
 * Pipeline stages used for error classification and reporting.
 */
public enum OperationStage {

    CONFIGURATION,
    GITHUB_AUTH,
    GITHUB_API,
    DOWNLOAD,
    ARCHIVE,
    MANIFEST,
    INTEGRITY,
    STAGING,
    BACKUP,
    INSTALLATION,
    NEXO_RELOAD,
    ROLLBACK,
    DISCORD,
    STATE;

    public String display() {
        return name().replace('_', ' ');
    }
}
