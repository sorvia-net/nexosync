package com.sorvia.nexosync.config;

import com.sorvia.nexosync.util.FileUtils;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Immutable, typed view of {@code config.yml}.
 *
 * <p>The file is read once per load and converted here, so the rest of the plugin never touches
 * Bukkit configuration objects and never has to guess at defaults. Secret-bearing values are passed
 * through {@link Secrets} during conversion.</p>
 */
public record NexoSyncConfig(
        PluginSection plugin,
        GitHubSection github,
        SyncSection sync,
        VersioningSection versioning,
        UpdateSection update,
        BackupSection backup,
        RollbackSection rollback,
        NexoSection nexo,
        SafetySection safety,
        DiscordSection discord,
        LoggingSection logging) {

    // -----------------------------------------------------------------------------------------
    // Sections
    // -----------------------------------------------------------------------------------------

    public record PluginSection(String language, boolean debug, String serverName) {
    }

    public record GitHubSection(
            String owner,
            String repository,
            String apiBaseUrl,
            String apiVersion,
            String token,
            boolean tokenReferenceUnresolved,
            ConnectionSection connection,
            RetrySection retry,
            ReleaseSection release) {

        public boolean hasToken() {
            return token != null && !token.isBlank();
        }

        /**
         * True when owner and repository were actually filled in. The shipped defaults are
         * placeholders and must not be treated as a usable repository.
         */
        public boolean isConfigured() {
            return owner != null && !owner.isBlank()
                    && repository != null && !repository.isBlank()
                    && !owner.startsWith("YOUR_")
                    && !repository.startsWith("YOUR_");
        }

        public String repositorySlug() {
            return owner + "/" + repository;
        }

        public record ConnectionSection(int connectTimeoutSeconds, int requestTimeoutSeconds, int downloadTimeoutSeconds) {
        }

        public record RetrySection(boolean enabled, int maxAttempts, long delayMilliseconds) {
        }

        public record ReleaseSection(
                String tagPrefix,
                String nameFormat,
                boolean allowDrafts,
                boolean allowPrereleases,
                AssetSection asset) {

            public record AssetSection(String nameFormat, String contentType) {

                public String resolveName(int version) {
                    return nameFormat.replace("{version}", Integer.toString(version));
                }
            }
        }
    }

    public record SyncSection(String nexoDirectory, List<String> paths, SyncMode mode, boolean requireNexoDirectory) {
    }

    public record VersioningSection(
            String mode,
            int initialVersion,
            String tagFormat,
            String nameFormat,
            boolean refuseExistingTag,
            boolean skipIdenticalSnapshot) {

        public String resolveTag(int version) {
            return tagFormat.replace("{version}", Integer.toString(version));
        }

        public String resolveName(int version) {
            return nameFormat.replace("{version}", Integer.toString(version));
        }
    }

    public record UpdateSection(
            boolean enabled,
            int checkIntervalSeconds,
            boolean checkOnStartup,
            int startupDelaySeconds,
            boolean autoUpdate,
            boolean requireNewerVersion,
            boolean allowDowngrade,
            boolean preventConcurrentUpdates) {
    }

    public record BackupSection(boolean enabled, String directory, int keepLast, boolean compress, boolean backupBeforeApply) {
    }

    public record RollbackSection(
            boolean enabled,
            boolean rollbackOnReloadFailure,
            boolean rollbackOnVerificationFailure,
            boolean reloadAfterRollback,
            int timeoutSeconds) {
    }

    public record NexoSection(boolean required, ReloadSection reload, int startupDelaySeconds) {

        public record ReloadSection(ReloadMode mode, String command, int timeoutSeconds, boolean failOnSevereLog) {
        }
    }

    public record SafetySection(
            int maxPackageSizeMb,
            int maxFileCount,
            boolean rejectSymbolicLinks,
            boolean rejectPathTraversal,
            boolean strictManagedPaths) {

        public long maxPackageSizeBytes() {
            return (long) maxPackageSizeMb * 1024L * 1024L;
        }

        /**
         * Uncompressed content is allowed to be larger than the download itself, but not without a
         * bound; four times the package limit is generous for YAML and resource-pack content.
         */
        public long maxUncompressedBytes() {
            return maxPackageSizeBytes() * 4L;
        }
    }

    public record DiscordSection(
            boolean enabled,
            String webhookUrl,
            boolean webhookReferenceUnresolved,
            String username,
            String avatarUrl,
            Map<String, Boolean> events,
            IncludeSection include) {

        public boolean isActive() {
            return enabled && webhookUrl != null && !webhookUrl.isBlank();
        }

        public boolean isEventEnabled(String key, boolean fallback) {
            return events.getOrDefault(key, fallback);
        }

        public record IncludeSection(
                boolean serverName,
                boolean snapshotVersion,
                boolean fileCount,
                boolean duration,
                boolean changedFiles,
                boolean errorDetails) {
        }
    }

    public record LoggingSection(ConsoleSection console, FileSection file, boolean debug) {

        public record ConsoleSection(boolean startup, boolean updates, boolean errors) {
        }

        public record FileSection(boolean enabled, String path) {
        }
    }

    // -----------------------------------------------------------------------------------------
    // Loading
    // -----------------------------------------------------------------------------------------

    public static NexoSyncConfig load(FileConfiguration raw) {
        return new NexoSyncConfig(
                loadPlugin(section(raw, "plugin")),
                loadGitHub(section(raw, "github")),
                loadSync(section(raw, "sync")),
                loadVersioning(section(raw, "versioning")),
                loadUpdate(section(raw, "update")),
                loadBackup(section(raw, "backup")),
                loadRollback(section(raw, "rollback")),
                loadNexo(section(raw, "nexo")),
                loadSafety(section(raw, "safety")),
                loadDiscord(section(raw, "discord")),
                loadLogging(section(raw, "logging")));
    }

    private static PluginSection loadPlugin(ConfigurationSection section) {
        return new PluginSection(
                string(section, "language", "en_US"),
                bool(section, "debug", false),
                Secrets.expand(string(section, "server-name", "")));
    }

    private static GitHubSection loadGitHub(ConfigurationSection section) {
        String rawToken = string(section, "token", "");
        String token = Secrets.expand(rawToken).trim();

        ConfigurationSection connection = child(section, "connection");
        ConfigurationSection retry = child(section, "retry");
        ConfigurationSection release = child(section, "release");
        ConfigurationSection asset = child(release, "asset");

        return new GitHubSection(
                Secrets.expand(string(section, "owner", "")).trim(),
                Secrets.expand(string(section, "repository", "")).trim(),
                trimTrailingSlash(string(section, "api-base-url", "https://api.github.com")),
                string(section, "api-version", "2022-11-28"),
                token,
                Secrets.isUnresolved(rawToken, token),
                new GitHubSection.ConnectionSection(
                        clamp(integer(connection, "connect-timeout-seconds", 10), 1, 300),
                        clamp(integer(connection, "request-timeout-seconds", 30), 1, 600),
                        clamp(integer(connection, "download-timeout-seconds", 120), 1, 3600)),
                new GitHubSection.RetrySection(
                        bool(retry, "enabled", true),
                        clamp(integer(retry, "max-attempts", 3), 1, 10),
                        clamp(integer(retry, "delay-milliseconds", 1500), 100, 60_000)),
                new GitHubSection.ReleaseSection(
                        string(release, "tag-prefix", "nexosync-v"),
                        string(release, "name-format", "NexoSync Snapshot v{version}"),
                        bool(release, "allow-drafts", false),
                        bool(release, "allow-prereleases", false),
                        new GitHubSection.ReleaseSection.AssetSection(
                                string(asset, "name-format", "nexo-snapshot-v{version}.zip"),
                                string(asset, "content-type", "application/zip"))));
    }

    private static SyncSection loadSync(ConfigurationSection section) {
        List<String> configured = section == null
                ? List.of()
                : section.getStringList("paths");

        // Preserve order, drop duplicates and anything that is not a safe relative path.
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (String candidate : configured) {
            if (candidate == null) {
                continue;
            }
            String normalized = FileUtils.normalize(candidate.trim());
            while (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            if (FileUtils.isSafeRelativePath(normalized)) {
                paths.add(normalized);
            }
        }
        if (paths.isEmpty()) {
            paths.add("glyphs");
            paths.add("items");
            paths.add("pack/external_packs");
        }

        return new SyncSection(
                string(section, "nexo-directory", "plugins/Nexo"),
                List.copyOf(paths),
                SyncMode.parse(string(section, "mode", "both"), SyncMode.BOTH),
                bool(section, "require-nexo-directory", true));
    }

    private static VersioningSection loadVersioning(ConfigurationSection section) {
        return new VersioningSection(
                string(section, "mode", "increment"),
                Math.max(1, integer(section, "initial-version", 1)),
                string(section, "tag-format", "nexosync-v{version}"),
                string(section, "name-format", "NexoSync Snapshot v{version}"),
                bool(section, "refuse-existing-tag", true),
                bool(section, "skip-identical-snapshot", true));
    }

    private static UpdateSection loadUpdate(ConfigurationSection section) {
        return new UpdateSection(
                bool(section, "enabled", true),
                clamp(integer(section, "check-interval-seconds", 60), 10, 86_400),
                bool(section, "check-on-startup", true),
                clamp(integer(section, "startup-delay-seconds", 30), 0, 3600),
                bool(section, "auto-update", true),
                bool(section, "require-newer-version", true),
                bool(section, "allow-downgrade", false),
                bool(section, "prevent-concurrent-updates", true));
    }

    private static BackupSection loadBackup(ConfigurationSection section) {
        return new BackupSection(
                bool(section, "enabled", true),
                string(section, "directory", "plugins/NexoSync/backups"),
                clamp(integer(section, "keep-last", 5), 1, 100),
                bool(section, "compress", true),
                bool(section, "backup-before-apply", true));
    }

    private static RollbackSection loadRollback(ConfigurationSection section) {
        return new RollbackSection(
                bool(section, "enabled", true),
                bool(section, "rollback-on-reload-failure", true),
                bool(section, "rollback-on-verification-failure", true),
                bool(section, "reload-after-rollback", true),
                clamp(integer(section, "timeout-seconds", 120), 5, 3600));
    }

    private static NexoSection loadNexo(ConfigurationSection section) {
        ConfigurationSection reload = child(section, "reload");
        return new NexoSection(
                bool(section, "required", true),
                new NexoSection.ReloadSection(
                        ReloadMode.parse(string(reload, "mode", "auto"), ReloadMode.AUTO),
                        string(reload, "command", "nexo reload"),
                        clamp(integer(reload, "timeout-seconds", 120), 5, 3600),
                        bool(reload, "fail-on-severe-log", true)),
                clamp(integer(section, "startup-delay-seconds", 10), 0, 3600));
    }

    private static SafetySection loadSafety(ConfigurationSection section) {
        return new SafetySection(
                clamp(integer(section, "max-package-size-mb", 512), 1, 8192),
                clamp(integer(section, "max-file-count", 10_000), 1, 1_000_000),
                bool(section, "reject-symbolic-links", true),
                bool(section, "reject-path-traversal", true),
                bool(section, "strict-managed-paths", true));
    }

    private static DiscordSection loadDiscord(ConfigurationSection section) {
        String rawWebhook = string(section, "webhook-url", "");
        String webhook = Secrets.expand(rawWebhook).trim();

        Map<String, Boolean> events = new LinkedHashMap<>();
        ConfigurationSection eventSection = child(section, "events");
        if (eventSection != null) {
            for (String key : eventSection.getKeys(false)) {
                events.put(key, eventSection.getBoolean(key));
            }
        }

        ConfigurationSection include = child(section, "include");
        return new DiscordSection(
                bool(section, "enabled", false),
                webhook,
                Secrets.isUnresolved(rawWebhook, webhook),
                string(section, "username", "NexoSync"),
                string(section, "avatar-url", ""),
                Map.copyOf(events),
                new DiscordSection.IncludeSection(
                        bool(include, "server-name", true),
                        bool(include, "snapshot-version", true),
                        bool(include, "file-count", true),
                        bool(include, "duration", true),
                        bool(include, "changed-files", true),
                        bool(include, "error-details", true)));
    }

    private static LoggingSection loadLogging(ConfigurationSection section) {
        ConfigurationSection console = child(section, "console");
        ConfigurationSection file = child(section, "file");
        ConfigurationSection debug = child(section, "debug");
        return new LoggingSection(
                new LoggingSection.ConsoleSection(
                        bool(console, "startup", true),
                        bool(console, "updates", true),
                        bool(console, "errors", true)),
                new LoggingSection.FileSection(
                        bool(file, "enabled", true),
                        string(file, "path", "plugins/NexoSync/logs/nexosync.log")),
                bool(debug, "enabled", false));
    }

    // -----------------------------------------------------------------------------------------
    // Validation
    // -----------------------------------------------------------------------------------------

    /**
     * Returns human-readable problems that make this configuration unusable or dangerous. An empty
     * list means NexoSync can run; warnings are reported separately by {@link #warnings()}.
     */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();

        if (!github.isConfigured()) {
            problems.add("github.owner / github.repository are still set to their placeholder values.");
        }
        if (github.tokenReferenceUnresolved()) {
            problems.add("github.token references an environment variable that is not set on this machine.");
        }
        if (sync.paths().isEmpty()) {
            problems.add("sync.paths is empty; there is nothing to synchronize.");
        }
        if (discord.enabled() && discord.webhookReferenceUnresolved()) {
            problems.add("discord.webhook-url references an environment variable that is not set on this machine.");
        }
        return problems;
    }

    /**
     * Returns non-fatal observations worth printing once at startup.
     */
    public List<String> warnings() {
        List<String> warnings = new ArrayList<>();

        if (!github.hasToken()) {
            warnings.add("No GitHub token configured. Only public repositories will be readable, "
                    + "and publishing is not possible.");
        }
        if (!backup.enabled()) {
            warnings.add("Backups are disabled. A failed update cannot be rolled back automatically.");
        }
        if (github.release().allowPrereleases()) {
            warnings.add("Prereleases are eligible for automatic installation.");
        }
        if (github.release().allowDrafts()) {
            warnings.add("Draft releases are eligible for automatic installation.");
        }
        if (update.allowDowngrade()) {
            warnings.add("Downgrades are permitted; an older snapshot can replace a newer one.");
        }
        return warnings;
    }

    // -----------------------------------------------------------------------------------------
    // Small readers
    // -----------------------------------------------------------------------------------------

    private static ConfigurationSection section(FileConfiguration raw, String name) {
        return raw.getConfigurationSection(name);
    }

    private static ConfigurationSection child(ConfigurationSection parent, String name) {
        return parent == null ? null : parent.getConfigurationSection(name);
    }

    private static String string(ConfigurationSection section, String key, String fallback) {
        if (section == null) {
            return fallback;
        }
        String value = section.getString(key);
        return value == null ? fallback : value;
    }

    private static boolean bool(ConfigurationSection section, String key, boolean fallback) {
        return section == null ? fallback : section.getBoolean(key, fallback);
    }

    private static int integer(ConfigurationSection section, String key, int fallback) {
        return section == null ? fallback : section.getInt(key, fallback);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String trimTrailingSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
