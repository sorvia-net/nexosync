package com.sorvia.nexosync;

import com.sorvia.nexosync.command.NexoSyncCommand;
import com.sorvia.nexosync.config.NexoSyncConfig;
import com.sorvia.nexosync.engine.BackupManager;
import com.sorvia.nexosync.engine.EngineContext;
import com.sorvia.nexosync.engine.OperationLock;
import com.sorvia.nexosync.engine.SyncOrchestrator;
import com.sorvia.nexosync.github.GitHubClient;
import com.sorvia.nexosync.lang.LanguageManager;
import com.sorvia.nexosync.log.NexoSyncLogger;
import com.sorvia.nexosync.nexo.NexoBridge;
import com.sorvia.nexosync.notify.DiscordNotifier;
import com.sorvia.nexosync.platform.PlatformScheduler;
import com.sorvia.nexosync.snapshot.SnapshotService;
import com.sorvia.nexosync.state.StateStore;
import com.sorvia.nexosync.util.FileUtils;
import com.sorvia.nexosync.util.SecretMask;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * NexoSync - centralized Nexo snapshot management and automatic synchronization.
 *
 * <p>Developed by Sorvia Development Solutions.</p>
 *
 * <p>The plugin keeps selected Nexo directories identical across a network of Paper and Folia
 * servers by publishing them as immutable snapshots to GitHub Releases. It deliberately depends on
 * nothing but GitHub: no proxy plugin, no database, no server-to-server connection.</p>
 */
public final class NexoSyncPlugin extends JavaPlugin {

    private final SecretMask secretMask = new SecretMask();
    private final OperationLock operationLock = new OperationLock();

    private NexoSyncLogger log;
    private LanguageManager language;
    private PlatformScheduler scheduler;
    private StateStore stateStore;
    private SyncOrchestrator orchestrator;

    private Path serverRoot;

    // -----------------------------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------------------------

    @Override
    public void onEnable() {
        this.serverRoot = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        this.log = new NexoSyncLogger(getLogger(), secretMask);
        this.language = new LanguageManager(this);
        this.scheduler = new PlatformScheduler(this);

        saveDefaultConfig();
        prepareDataDirectories();

        this.stateStore = new StateStore(getDataFolder().toPath(), log);
        this.stateStore.load();

        if (!buildRuntime()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        registerCommand();
        orchestrator.start();
    }

    @Override
    public void onDisable() {
        if (orchestrator != null) {
            orchestrator.stop();
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
        if (log != null) {
            // The server already prints its own "Disabling NexoSync" line; this one is for the file.
            log.progress(null, "NexoSync disabled.");
        }
    }

    /**
     * Rebuilds configuration, language and every service.
     *
     * <p>A running operation holds its own references, so a reload never pulls the ground out from
     * under an update that is already in flight; the new services simply take over afterwards.</p>
     */
    public void reloadEverything() {
        if (orchestrator != null) {
            orchestrator.stop();
        }
        reloadConfig();
        secretMask.clear();

        if (buildRuntime()) {
            orchestrator.start();
        }
    }

    // -----------------------------------------------------------------------------------------
    // Wiring
    // -----------------------------------------------------------------------------------------

    /**
     * Loads the configuration and assembles the service graph.
     *
     * @return false when the environment is unusable and the plugin must not stay enabled
     */
    private boolean buildRuntime() {
        NexoSyncConfig config = NexoSyncConfig.load(getConfig());

        secretMask.register(config.github().token());
        secretMask.register(config.discord().webhookUrl());

        log.apply(config, serverRoot);
        language.load(config.plugin().language());

        Path nexoDirectory = serverRoot.resolve(config.sync().nexoDirectory()).normalize();
        Path backupDirectory = serverRoot.resolve(config.backup().directory()).normalize();

        if (!validateEnvironment(config, nexoDirectory)) {
            return false;
        }

        NexoBridge nexo = new NexoBridge(this, config, scheduler, log);
        DiscordNotifier discord = new DiscordNotifier(config, log, scheduler, resolveServerName(config));
        GitHubClient github = new GitHubClient(config, log, version());
        SnapshotService snapshots = new SnapshotService(config, nexoDirectory, log);
        BackupManager backups = new BackupManager(config, nexoDirectory, backupDirectory, log);

        EngineContext context = new EngineContext(this, version(), config, log, language, scheduler, stateStore,
                github, snapshots, backups, nexo, discord, operationLock, serverRoot, getDataFolder().toPath(),
                nexoDirectory);

        this.orchestrator = new SyncOrchestrator(context);

        reportConfiguration(config, nexo, nexoDirectory);
        return true;
    }

    /**
     * Refuses to run in a state where an operation could damage the server.
     */
    private boolean validateEnvironment(NexoSyncConfig config, Path nexoDirectory) {
        if (config.nexo().required() && Bukkit.getPluginManager().getPlugin("Nexo") == null) {
            getLogger().severe("Nexo was not detected. Install Nexo, or set nexo.required to false.");
            return false;
        }
        if (config.sync().requireNexoDirectory() && !Files.isDirectory(nexoDirectory)) {
            getLogger().severe("The configured Nexo directory does not exist: " + nexoDirectory);
            getLogger().severe("Start the server once with Nexo installed, or correct sync.nexo-directory.");
            return false;
        }
        return true;
    }

    /**
     * Prints the whole startup summary in two lines.
     *
     * <p>Everything an administrator needs to confirm at a glance - version, platform, Nexo, mode,
     * and what is actually being synchronized - and nothing else. Detail belongs in the log file.</p>
     */
    private void reportConfiguration(NexoSyncConfig config, NexoBridge nexo, Path nexoDirectory) {
        log.startup("NexoSync " + version() + " by Sorvia Development Solutions"
                + " | " + scheduler.platformName() + " " + Bukkit.getMinecraftVersion()
                + " | Nexo " + nexo.version()
                + " | mode " + config.sync().mode());
        log.startup("Synchronizing " + String.join(", ", config.sync().paths())
                + " in " + displayPath(nexoDirectory));

        // Recorded so that a Nexo version which moves or renames its reload entry point is visible
        // in the log, rather than only showing up as content that quietly fails to refresh.
        log.progress(null, "Nexo reload mode " + config.nexo().reload().mode()
                + " will use " + nexo.describeReloadTarget());

        if (!nexo.isPresent()) {
            getLogger().warning("Nexo was not detected; snapshots cannot be reloaded on this server.");
        }

        for (String problem : config.problems()) {
            getLogger().severe("Configuration problem: " + problem);
        }
        for (String warning : config.warnings()) {
            getLogger().warning(warning);
        }
        if (!config.problems().isEmpty()) {
            getLogger().severe("NexoSync is loaded but will not synchronize until the problems above are resolved.");
        }
    }

    /**
     * Shortens a path to its location relative to the server root, so the console shows
     * {@code plugins/Nexo} rather than a full absolute path.
     */
    private String displayPath(Path path) {
        try {
            return serverRoot.relativize(path).toString().replace('\\', '/');
        } catch (IllegalArgumentException differentRoot) {
            return path.toString();
        }
    }

    private void prepareDataDirectories() {
        try {
            Path data = getDataFolder().toPath();
            FileUtils.ensureDirectory(data.resolve("lang"));
            FileUtils.ensureDirectory(data.resolve("cache"));
            FileUtils.ensureDirectory(data.resolve("logs"));

            // A staging directory left behind by a crash is never reused.
            FileUtils.deleteRecursively(data.resolve("cache").resolve("staging"));
            FileUtils.deleteRecursively(data.resolve("cache").resolve("work"));
        } catch (IOException setupFailed) {
            getLogger().warning("Unable to prepare the data directory: " + setupFailed.getMessage());
        }
    }

    private void registerCommand() {
        PluginCommand command = getCommand("nexosync");
        if (command == null) {
            getLogger().severe("The nexosync command is missing from plugin.yml; the plugin JAR may be corrupt.");
            return;
        }
        NexoSyncCommand executor = new NexoSyncCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    /**
     * Resolves the name used in reports. An explicit value wins; otherwise the server MOTD is used,
     * which is usually already the human-readable name of the server.
     */
    private String resolveServerName(NexoSyncConfig config) {
        String configured = config.plugin().serverName();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        String motd = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(Bukkit.getServer().motd());
        if (!motd.isBlank()) {
            return motd.replaceAll("\\s+", " ").strip();
        }
        return "Minecraft Server";
    }

    // -----------------------------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------------------------

    public LanguageManager language() {
        return language;
    }

    public SyncOrchestrator orchestrator() {
        return orchestrator;
    }

    public NexoSyncLogger logging() {
        return log;
    }

    public String version() {
        return getPluginMeta().getVersion();
    }
}
