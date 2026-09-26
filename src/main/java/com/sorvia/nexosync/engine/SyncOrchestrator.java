package com.sorvia.nexosync.engine;

import com.sorvia.nexosync.NexoSyncException;
import com.sorvia.nexosync.config.NexoSyncConfig;
import com.sorvia.nexosync.github.GitHubException;
import com.sorvia.nexosync.github.GitHubRelease;
import com.sorvia.nexosync.notify.DiscordEvent;
import com.sorvia.nexosync.notify.DiscordReport;
import com.sorvia.nexosync.util.OperationId;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Entry point for every synchronization operation.
 *
 * <p>Commands and the scheduled task both go through here, which is what makes the concurrency rule
 * enforceable in one place: an operation takes the lock, or it is refused. All work runs off the
 * server thread; only the Nexo reload is handed back to it.</p>
 */
public final class SyncOrchestrator {

    private final EngineContext context;
    private final UpdateEngine updateEngine;
    private final PushService pushService;
    private final RollbackService rollbackService;
    private final RecoveryService recoveryService;

    private final AtomicLong nextCheckAt = new AtomicLong(0L);

    private ScheduledFuture<?> scheduledCheck;
    private ScheduledFuture<?> startupTask;

    public SyncOrchestrator(EngineContext context) {
        this.context = context;
        this.rollbackService = new RollbackService(context);
        this.updateEngine = new UpdateEngine(context, rollbackService);
        this.pushService = new PushService(context);
        this.recoveryService = new RecoveryService(context, rollbackService);
    }

    public EngineContext context() {
        return context;
    }

    // -----------------------------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------------------------

    /**
     * Schedules the startup work and, when enabled, the periodic check.
     *
     * <p>The startup delay exists so NexoSync never races Nexo's own initialisation: verifying or
     * replacing item definitions while Nexo is still loading them would be meaningless.</p>
     */
    public void start() {
        NexoSyncConfig config = context.config();
        long startupDelay = Math.max(config.nexo().startupDelaySeconds(), config.update().startupDelaySeconds());

        startupTask = context.scheduler().delayed(() -> {
            String operationId = OperationId.next();

            RecoveryService.Outcome outcome = recoveryService.recover(operationId);
            if (outcome == RecoveryService.Outcome.FAILED) {
                context.log().error(operationId,
                        "Automatic update checks are paused because the managed directories need manual review.");
                return;
            }

            context.discord().send(operationId, DiscordEvent.STARTUP,
                    new DiscordReport("NexoSync Started", DiscordReport.COLOR_INFO)
                            .fieldIf(context.discord().include().serverName(), "Server",
                                    context.discord().serverName())
                            .field("Mode", config.sync().mode().name())
                            .field("Installed Snapshot",
                                    describeVersion(context.state().current().installedSnapshotOrZero())));

            if (config.update().enabled() && config.update().checkOnStartup() && config.sync().mode().canReceive()) {
                runScheduledCheck();
            }
            scheduleRecurringCheck();

        }, startupDelay);
    }

    private void scheduleRecurringCheck() {
        NexoSyncConfig config = context.config();
        if (!config.update().enabled() || !config.sync().mode().canReceive()) {
            context.log().startup("Automatic update checks are disabled.");
            return;
        }
        if (!config.github().isConfigured() || !config.problems().isEmpty()) {
            context.log().startup("Automatic update checks are paused until the configuration is completed.");
            return;
        }

        long interval = config.update().checkIntervalSeconds();
        nextCheckAt.set(System.currentTimeMillis() + interval * 1000L);

        scheduledCheck = context.scheduler().repeating(this::runScheduledCheck, interval, interval);
        context.log().startup("Checking GitHub for new snapshots every " + interval + " seconds.");
    }

    public void stop() {
        if (scheduledCheck != null) {
            scheduledCheck.cancel(false);
        }
        if (startupTask != null) {
            startupTask.cancel(false);
        }
    }

    /**
     * Seconds remaining until the next scheduled check, or empty when checks are disabled.
     */
    public Optional<Long> secondsUntilNextCheck() {
        long target = nextCheckAt.get();
        if (target <= 0L) {
            return Optional.empty();
        }
        return Optional.of(Math.max(0L, (target - System.currentTimeMillis()) / 1000L));
    }

    // -----------------------------------------------------------------------------------------
    // Scheduled check
    // -----------------------------------------------------------------------------------------

    /**
     * The periodic task: metadata check first, installation only when something changed.
     */
    private void runScheduledCheck() {
        NexoSyncConfig config = context.config();
        nextCheckAt.set(System.currentTimeMillis() + config.update().checkIntervalSeconds() * 1000L);

        if (config.update().preventConcurrentUpdates() && context.lock().isBusy()) {
            context.log().debug(null, "Skipping the scheduled check, another operation is running.");
            return;
        }

        String operationId = OperationId.next();
        Optional<OperationLock.Handle> handle = context.lock().tryAcquire("scheduled check", operationId);
        if (handle.isEmpty()) {
            return;
        }

        try (OperationLock.Handle lock = handle.get()) {
            UpdateEngine.CheckResult check = updateEngine.check(operationId);

            if (!check.updateAvailable()) {
                context.log().debug(operationId, "No new snapshot (local v" + check.localVersion()
                        + ", remote " + describeVersion(check.remoteVersion()) + ").");
                context.discord().send(operationId, DiscordEvent.NO_UPDATE,
                        new DiscordReport("No Nexo Snapshot Update", DiscordReport.COLOR_INFO)
                                .fieldIf(context.discord().include().serverName(), "Server",
                                        context.discord().serverName())
                                .field("Installed", describeVersion(check.localVersion())));
                return;
            }

            GitHubRelease release = check.latest().orElseThrow();

            // With auto-update on, the install result follows immediately and names both versions,
            // so announcing the discovery separately would just double every update to two lines.
            String announcement = "New snapshot available: v" + release.snapshotVersion()
                    + " (installed: " + describeVersion(check.localVersion()) + ").";
            if (config.update().autoUpdate()) {
                context.log().progress(operationId, announcement);
            } else {
                context.log().info(operationId, announcement);
            }

            context.discord().send(operationId, DiscordEvent.UPDATE_AVAILABLE,
                    new DiscordReport("Nexo Snapshot Available", DiscordReport.COLOR_INFO)
                            .fieldIf(context.discord().include().serverName(), "Server",
                                    context.discord().serverName())
                            .field("Installed", describeVersion(check.localVersion()))
                            .field("Available", "v" + release.snapshotVersion()));

            if (!config.update().autoUpdate()) {
                context.log().info(operationId,
                        "Automatic installation is disabled; run /nexosync update to install it.");
                return;
            }
            updateEngine.update(operationId, release);

        } catch (NexoSyncException checkFailed) {
            handleCheckFailure(operationId, checkFailed);
        }
    }

    private void handleCheckFailure(String operationId, NexoSyncException failure) {
        context.log().error(operationId, "Update check failed: " + failure.getMessage(), failure);
        context.state().recordError(failure.stage().display() + ": " + failure.getMessage());

        context.discord().send(operationId, DiscordEvent.CHECK_FAILURE,
                new DiscordReport("Nexo Snapshot Check Failed", DiscordReport.COLOR_FAILURE)
                        .fieldIf(context.discord().include().serverName(), "Server", context.discord().serverName())
                        .field("Failed Stage", failure.stage().display())
                        .blockIf(context.discord().include().errorDetails(), "Reason",
                                "```\n" + failure.getMessage() + "\n```"));
    }

    // -----------------------------------------------------------------------------------------
    // Command entry points
    // -----------------------------------------------------------------------------------------

    public void commandCheck(CommandSender sender) {
        run(sender, "check", operationId -> {
            context.lang().send(sender, "check.started");
            UpdateEngine.CheckResult check = updateEngine.check(operationId);

            context.lang().send(sender, "check.current", "version",
                    context.lang().versionLabel(check.localVersion()));
            if (check.latest().isEmpty()) {
                context.lang().send(sender, "check.no-release");
                return;
            }
            context.lang().send(sender, "check.latest", "version", "v" + check.remoteVersion());

            if (check.updateAvailable()) {
                context.lang().send(sender, "check.available", "version", "v" + check.remoteVersion());
            } else {
                context.lang().send(sender, "check.up-to-date");
            }
        });
    }

    public void commandUpdate(CommandSender sender) {
        if (!context.config().sync().mode().canReceive()) {
            context.lang().send(sender, "general.mode-not-receiver",
                    "mode", context.config().sync().mode().name());
            return;
        }

        run(sender, "manual update", operationId -> {
            context.lang().send(sender, "check.started");
            UpdateEngine.CheckResult check = updateEngine.check(operationId);

            if (check.latest().isEmpty()) {
                context.lang().send(sender, "check.no-release");
                return;
            }
            if (!check.updateAvailable()) {
                context.lang().send(sender, "check.up-to-date");
                return;
            }

            GitHubRelease release = check.latest().orElseThrow();
            context.lang().send(sender, "update.started");
            context.lang().send(sender, "update.downloading", "version", "v" + release.snapshotVersion());

            OperationResult result = updateEngine.update(operationId, release);
            reportUpdateResult(sender, release.snapshotVersion(), result);
        });
    }

    public void commandPush(CommandSender sender) {
        if (!context.config().sync().mode().canPush()) {
            context.lang().send(sender, "general.mode-not-publisher",
                    "mode", context.config().sync().mode().name());
            return;
        }

        run(sender, "publish", operationId -> {
            context.lang().send(sender, "push.started");

            try {
                PushService.PushOutcome outcome = pushService.push(operationId);
                if (outcome.published()) {
                    context.lang().send(sender, "push.success", "version", "v" + outcome.version());
                    if (outcome.diff() != null && !outcome.diff().isEmpty()) {
                        context.lang().send(sender, "push.changes", "summary", outcome.diff().summary());
                    }
                } else {
                    context.lang().send(sender, "push.identical");
                }
            } catch (NexoSyncException failure) {
                context.lang().send(sender, "push.failed");
                sendFailureDetail(sender, failure);
            }
        });
    }

    public void commandRollback(CommandSender sender, Integer version) {
        if (!context.config().rollback().enabled()) {
            context.lang().send(sender, "rollback.disabled");
            return;
        }

        run(sender, "rollback", operationId -> {
            Optional<BackupEntry> target = rollbackService.resolveTarget(version);
            if (target.isEmpty()) {
                context.lang().send(sender, "rollback.unavailable");
                listBackups(sender);
                return;
            }

            BackupEntry entry = target.get();
            context.lang().send(sender, "rollback.started", "version", "v" + entry.version());
            context.lang().send(sender, "rollback.restoring", "version", "v" + entry.version());

            OperationResult result = rollbackService.rollback(operationId, entry);
            if (result.isSuccess()) {
                context.lang().send(sender, "rollback.success");
            } else {
                context.lang().send(sender, "rollback.failed");
            }
        });
    }

    public void listBackups(CommandSender sender) {
        List<BackupEntry> entries = context.backups().list();
        if (entries.isEmpty()) {
            context.lang().send(sender, "rollback.no-backups");
            return;
        }
        context.lang().send(sender, "rollback.available-title");
        for (BackupEntry entry : entries) {
            context.lang().sendRaw(sender, "rollback.available-entry",
                    "id", entry.id(), "version", "v" + entry.version());
        }
    }

    public PushService pushService() {
        return pushService;
    }

    public UpdateEngine updateEngine() {
        return updateEngine;
    }

    public RecoveryService recoveryService() {
        return recoveryService;
    }

    // -----------------------------------------------------------------------------------------
    // Shared plumbing
    // -----------------------------------------------------------------------------------------

    /**
     * A unit of work that reports its own progress to the sender.
     */
    @FunctionalInterface
    public interface Operation {
        void run(String operationId) throws NexoSyncException;
    }

    /**
     * Runs an operation asynchronously under the operation lock, translating any escaping failure
     * into a message the sender can act on.
     */
    private void run(CommandSender sender, String description, Operation operation) {
        String operationId = OperationId.next();
        Optional<OperationLock.Handle> handle = context.lock().tryAcquire(description, operationId);

        if (handle.isEmpty()) {
            context.lang().send(sender, "general.operation-in-progress",
                    "operation", context.lock().currentOperation().orElse("unknown"));
            return;
        }

        try {
            context.scheduler().async(() -> {
                try (OperationLock.Handle lock = handle.get()) {
                    operation.run(operationId);
                } catch (NexoSyncException failure) {
                    context.log().error(operationId, description + " failed: " + failure.getMessage(), failure);
                    sendFailureDetail(sender, failure);
                } catch (RuntimeException unexpected) {
                    context.log().error(operationId, description + " failed unexpectedly.", unexpected);
                    context.lang().send(sender, "general.unexpected-error");
                }
            });
        } catch (RuntimeException notScheduled) {
            // The worker pool refuses new work while the plugin is shutting down; the lock would
            // otherwise stay held by an operation that never runs.
            handle.get().close();
            context.log().warn(operationId, description + " could not be scheduled: " + notScheduled.getMessage());
            context.lang().send(sender, "general.unexpected-error");
        }
    }

    private void sendFailureDetail(CommandSender sender, NexoSyncException failure) {
        if (failure instanceof GitHubException gitHubFailure) {
            context.lang().send(sender, gitHubFailure.languageKey());
        }
        context.lang().send(sender, "general.failure-detail",
                "stage", failure.stage().display(),
                "reason", context.log().mask().apply(failure.getMessage()));
    }

    private void reportUpdateResult(CommandSender sender, int version, OperationResult result) {
        if (result.isSuccess()) {
            context.lang().send(sender, "update.success", "version", "v" + version);
            result.diff().ifPresent(diff ->
                    context.lang().send(sender, "update.changes", "summary", diff.summary()));
            return;
        }

        context.lang().send(sender, "update.failed", "version", "v" + version);
        context.lang().send(sender, "general.failure-detail",
                "stage", result.failedStage().map(OperationStage::display).orElse("UNKNOWN"),
                "reason", context.log().mask().apply(result.message()));

        if (result.wasRolledBack()) {
            context.lang().send(sender, "rollback.success");
        } else if (result.rollbackFailed()) {
            context.lang().send(sender, "rollback.failed");
        }
    }

    public static String describeVersion(int version) {
        return version > 0 ? "v" + version : "none";
    }
}
