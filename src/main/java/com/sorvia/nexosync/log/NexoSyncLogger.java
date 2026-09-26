package com.sorvia.nexosync.log;

import com.sorvia.nexosync.config.NexoSyncConfig;
import com.sorvia.nexosync.util.SecretMask;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Console and file logging with operation correlation.
 *
 * <p>Every message carries the operation identifier of the work that produced it, so a single
 * update can be followed from the GitHub request through to the Discord notification. Every message
 * also passes through {@link SecretMask} before it is written anywhere.</p>
 */
public final class NexoSyncLogger {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Logger logger;
    private final SecretMask mask;
    private final Object fileLock = new Object();

    private volatile boolean debugEnabled;
    private volatile boolean consoleStartup = true;
    private volatile boolean consoleUpdates = true;
    private volatile boolean consoleErrors = true;
    private volatile boolean fileEnabled;
    private volatile Path logFile;
    private volatile boolean fileFailureReported;

    public NexoSyncLogger(Logger logger, SecretMask mask) {
        this.logger = logger;
        this.mask = mask;
    }

    public void apply(NexoSyncConfig config, Path serverRoot) {
        this.debugEnabled = config.plugin().debug() || config.logging().debug();
        this.consoleStartup = config.logging().console().startup();
        this.consoleUpdates = config.logging().console().updates();
        this.consoleErrors = config.logging().console().errors();
        this.fileEnabled = config.logging().file().enabled();
        this.logFile = serverRoot.resolve(config.logging().file().path()).normalize();
        this.fileFailureReported = false;
    }

    public SecretMask mask() {
        return mask;
    }

    public boolean isDebugEnabled() {
        return debugEnabled;
    }

    // -----------------------------------------------------------------------------------------
    // Startup / lifecycle messages
    // -----------------------------------------------------------------------------------------

    public void startup(String message) {
        String safe = mask.apply(message);
        if (consoleStartup) {
            logger.info(safe);
        }
        writeToFile("INFO", null, safe);
    }

    // -----------------------------------------------------------------------------------------
    // Operation messages
    // -----------------------------------------------------------------------------------------

    /**
     * A milestone worth putting on the console: an update finished, a rollback ran, a new snapshot
     * was published. One line per outcome, not per step.
     */
    public void info(String operationId, String message) {
        String safe = mask.apply(message);
        if (consoleUpdates) {
            logger.info(prefix(operationId) + safe);
        }
        writeToFile("INFO", operationId, safe);
    }

    /**
     * A step inside an operation - scanning, packaging, downloading, installing.
     *
     * <p>Always recorded in the log file, because that is the audit trail an administrator needs
     * after something went wrong. Printed to the console only in debug mode, because printing every
     * step of every sixty-second cycle buries the one line that actually mattered.</p>
     */
    public void progress(String operationId, String message) {
        String safe = mask.apply(message);
        if (debugEnabled && consoleUpdates) {
            logger.info(prefix(operationId) + safe);
        }
        writeToFile("INFO", operationId, safe);
    }

    public void warn(String operationId, String message) {
        String safe = mask.apply(message);
        logger.warning(prefix(operationId) + safe);
        writeToFile("WARN", operationId, safe);
    }

    public void error(String operationId, String message) {
        error(operationId, message, null);
    }

    public void error(String operationId, String message, Throwable throwable) {
        String safe = mask.apply(message);
        String detail = throwable == null ? "" : " (" + mask.describe(throwable) + ")";

        if (consoleErrors) {
            if (throwable != null && debugEnabled) {
                logger.log(Level.SEVERE, prefix(operationId) + safe + detail, throwable);
            } else {
                logger.severe(prefix(operationId) + safe + detail);
            }
        }
        writeToFile("ERROR", operationId, safe + detail);
    }

    public void debug(String operationId, String message) {
        if (!debugEnabled) {
            return;
        }
        String safe = mask.apply(message);
        logger.info(prefix(operationId) + "[debug] " + safe);
        writeToFile("DEBUG", operationId, safe);
    }

    private static String prefix(String operationId) {
        return operationId == null || operationId.isBlank() ? "" : "[" + operationId + "] ";
    }

    // -----------------------------------------------------------------------------------------
    // File sink
    // -----------------------------------------------------------------------------------------

    private void writeToFile(String level, String operationId, String message) {
        Path target = logFile;
        if (!fileEnabled || target == null) {
            return;
        }
        String line = ZonedDateTime.now().format(TIMESTAMP)
                + " [" + level + "] "
                + prefix(operationId)
                + message
                + System.lineSeparator();

        synchronized (fileLock) {
            try {
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                Files.writeString(target, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException writeFailed) {
                // Never let logging break an operation; report the problem once and stop trying.
                if (!fileFailureReported) {
                    fileFailureReported = true;
                    fileEnabled = false;
                    logger.warning("File logging disabled, unable to write " + target + ": " + writeFailed.getMessage());
                }
            }
        }
    }
}
