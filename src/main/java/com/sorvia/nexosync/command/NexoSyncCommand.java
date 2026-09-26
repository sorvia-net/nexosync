package com.sorvia.nexosync.command;

import com.sorvia.nexosync.NexoSyncException;
import com.sorvia.nexosync.NexoSyncPlugin;
import com.sorvia.nexosync.engine.EngineContext;
import com.sorvia.nexosync.engine.SyncOrchestrator;
import com.sorvia.nexosync.engine.UpdateEngine;
import com.sorvia.nexosync.github.GitHubClient;
import com.sorvia.nexosync.lang.LanguageManager;
import com.sorvia.nexosync.util.OperationId;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The {@code /nexosync} command tree.
 *
 * <p>The class stays deliberately thin: it validates permission and arguments, then hands the work
 * to {@link SyncOrchestrator}. Nothing here blocks the calling thread.</p>
 */
public final class NexoSyncCommand implements TabExecutor {

    private static final String PERMISSION = "nexosync.admin";

    private static final List<String> SUBCOMMANDS =
            List.of("help", "push", "check", "update", "status", "rollback", "backups", "reload", "version");

    private final NexoSyncPlugin plugin;

    public NexoSyncCommand(NexoSyncPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             String @NotNull [] args) {

        LanguageManager lang = plugin.language();

        if (!sender.hasPermission(PERMISSION)) {
            lang.send(sender, "general.no-permission");
            return true;
        }
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        SyncOrchestrator orchestrator = plugin.orchestrator();
        if (orchestrator == null) {
            lang.send(sender, "general.not-ready");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help" -> sendHelp(sender);
            case "push" -> orchestrator.commandPush(sender);
            case "check" -> orchestrator.commandCheck(sender);
            case "update" -> orchestrator.commandUpdate(sender);
            case "status" -> sendStatus(sender, orchestrator);
            case "backups" -> orchestrator.listBackups(sender);
            case "reload" -> reloadPlugin(sender);
            case "version" -> sendVersion(sender, orchestrator.context());
            case "rollback" -> handleRollback(sender, orchestrator, args);
            default -> lang.send(sender, "general.unknown-command");
        }
        return true;
    }

    // -----------------------------------------------------------------------------------------
    // Subcommands
    // -----------------------------------------------------------------------------------------

    private void handleRollback(CommandSender sender, SyncOrchestrator orchestrator, String[] args) {
        if (args.length == 1) {
            orchestrator.commandRollback(sender, null);
            return;
        }
        if (args[1].equalsIgnoreCase("list")) {
            orchestrator.listBackups(sender);
            return;
        }

        String raw = args[1].startsWith("v") || args[1].startsWith("V") ? args[1].substring(1) : args[1];
        try {
            orchestrator.commandRollback(sender, Integer.parseInt(raw));
        } catch (NumberFormatException notANumber) {
            plugin.language().send(sender, "rollback.invalid-version", "input", args[1]);
        }
    }

    private void sendHelp(CommandSender sender) {
        LanguageManager lang = plugin.language();
        lang.sendRaw(sender, "commands.help.title");
        lang.sendRaw(sender, "commands.help.push");
        lang.sendRaw(sender, "commands.help.check");
        lang.sendRaw(sender, "commands.help.update");
        lang.sendRaw(sender, "commands.help.status");
        lang.sendRaw(sender, "commands.help.rollback");
        lang.sendRaw(sender, "commands.help.backups");
        lang.sendRaw(sender, "commands.help.reload");
        lang.sendRaw(sender, "commands.help.version");
    }

    private void reloadPlugin(CommandSender sender) {
        LanguageManager lang = plugin.language();
        lang.send(sender, "reload.started");
        try {
            plugin.reloadEverything();
            plugin.language().send(sender, "reload.success");
        } catch (RuntimeException reloadFailed) {
            plugin.getLogger().severe("Configuration reload failed: " + reloadFailed.getMessage());
            lang.send(sender, "reload.failed");
        }
    }

    private void sendVersion(CommandSender sender, EngineContext context) {
        LanguageManager lang = plugin.language();
        lang.sendRaw(sender, "version.title");
        lang.sendRaw(sender, "version.plugin", "version", context.pluginVersion());
        lang.sendRaw(sender, "version.minecraft", "version", Bukkit.getMinecraftVersion());
        lang.sendRaw(sender, "version.platform", "platform", context.scheduler().platformName());
        lang.sendRaw(sender, "version.nexo", "version", context.nexo().version());
        lang.sendRaw(sender, "version.java", "version", System.getProperty("java.version", "unknown"));
        lang.sendRaw(sender, "version.developer");
    }

    /**
     * Prints the local state immediately and fills in the GitHub half once the request returns, so
     * the command never blocks on the network.
     */
    private void sendStatus(CommandSender sender, SyncOrchestrator orchestrator) {
        EngineContext context = orchestrator.context();
        LanguageManager lang = plugin.language();

        int installed = context.state().current().installedSnapshotOrZero();

        lang.sendRaw(sender, "status.title");
        lang.sendRaw(sender, "status.mode", "mode", context.config().sync().mode().name());
        lang.sendRaw(sender, "status.installed", "version", lang.versionLabel(installed));
        lang.sendRaw(sender, "status.platform", "platform", context.scheduler().platformName());
        lang.sendRaw(sender, "status.nexo", "version", context.nexo().version());
        lang.sendRaw(sender, "status.repository", "repository",
                context.config().github().isConfigured()
                        ? context.config().github().repositorySlug()
                        : "not configured");

        orchestrator.secondsUntilNextCheck().ifPresentOrElse(
                seconds -> lang.sendRaw(sender, "status.next-check", "seconds", Long.toString(seconds)),
                () -> lang.sendRaw(sender, "status.checks-disabled"));

        if (context.lock().isBusy()) {
            lang.sendRaw(sender, "status.busy", "operation", context.lock().currentOperation().orElse("unknown"));
        }
        if (!context.config().github().isConfigured()) {
            return;
        }

        String operationId = OperationId.next();
        context.scheduler().async(() -> {
            try {
                GitHubClient.RepositoryInfo repository = context.github().repository(operationId);
                lang.sendRaw(sender, "status.github-ok", "repository", repository.fullName(),
                        "access", repository.canPush() ? "read/write" : "read");

                UpdateEngine.CheckResult check = orchestrator.updateEngine().check(operationId);
                lang.sendRaw(sender, "status.latest", "version", lang.versionLabel(check.remoteVersion()));
                lang.sendRaw(sender, "status.state", "state",
                        lang.plain(check.updateAvailable() ? "status.state-update-available" : "status.state-up-to-date"));

            } catch (NexoSyncException unreachable) {
                lang.sendRaw(sender, "status.github-failed", "reason",
                        context.log().mask().apply(unreachable.getMessage()));
            }
        });
    }

    // -----------------------------------------------------------------------------------------
    // Tab completion
    // -----------------------------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                                      String @NotNull [] args) {

        if (!sender.hasPermission(PERMISSION)) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("rollback")) {
            SyncOrchestrator orchestrator = plugin.orchestrator();
            List<String> options = new ArrayList<>();
            options.add("list");
            if (orchestrator != null) {
                orchestrator.context().backups().list()
                        .forEach(entry -> options.add(Integer.toString(entry.version())));
            }
            return filter(options, args[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower)).distinct().toList();
    }
}
