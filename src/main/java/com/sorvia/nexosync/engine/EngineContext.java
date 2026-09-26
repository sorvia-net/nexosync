package com.sorvia.nexosync.engine;

import com.sorvia.nexosync.config.NexoSyncConfig;
import com.sorvia.nexosync.github.GitHubClient;
import com.sorvia.nexosync.lang.LanguageManager;
import com.sorvia.nexosync.log.NexoSyncLogger;
import com.sorvia.nexosync.nexo.NexoBridge;
import com.sorvia.nexosync.notify.DiscordNotifier;
import com.sorvia.nexosync.platform.PlatformScheduler;
import com.sorvia.nexosync.snapshot.SnapshotService;
import com.sorvia.nexosync.state.StateStore;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;

/**
 * Everything the engine services need, assembled once per configuration load.
 *
 * <p>Passing a single context keeps the service constructors readable and makes a reload trivial:
 * the plugin builds a new context and replaces the services that hold it, rather than mutating
 * shared state while an operation might be running.</p>
 */
public record EngineContext(
        JavaPlugin plugin,
        String pluginVersion,
        NexoSyncConfig config,
        NexoSyncLogger log,
        LanguageManager lang,
        PlatformScheduler scheduler,
        StateStore state,
        GitHubClient github,
        SnapshotService snapshots,
        BackupManager backups,
        NexoBridge nexo,
        DiscordNotifier discord,
        OperationLock lock,
        Path serverRoot,
        Path dataFolder,
        Path nexoDirectory) {

    public Path cacheDirectory() {
        return dataFolder.resolve("cache");
    }

    public Path stagingDirectory() {
        return cacheDirectory().resolve("staging");
    }

    public Path downloadDirectory() {
        return cacheDirectory().resolve("downloads");
    }

    public Path workDirectory() {
        return cacheDirectory().resolve("work");
    }
}
