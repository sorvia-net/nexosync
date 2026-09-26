package com.sorvia.nexosync.engine;

import com.sorvia.nexosync.NexoSyncException;
import com.sorvia.nexosync.github.GitHubAsset;
import com.sorvia.nexosync.github.GitHubClient;
import com.sorvia.nexosync.github.GitHubRelease;
import com.sorvia.nexosync.notify.DiscordEvent;
import com.sorvia.nexosync.notify.DiscordReport;
import com.sorvia.nexosync.snapshot.SnapshotDiff;
import com.sorvia.nexosync.snapshot.SnapshotManifest;
import com.sorvia.nexosync.snapshot.SnapshotService;
import com.sorvia.nexosync.util.FileUtils;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The publisher side: turning the live Nexo directory into a GitHub release.
 *
 * <p>Two rules shape this class. A release is never overwritten - an existing tag aborts the
 * operation instead of replacing published content - and an unchanged snapshot is never republished,
 * so a repository's release history stays a meaningful record of actual content changes.</p>
 */
public final class PushService {

    /** Marker used to record a snapshot's content hash in the release body. */
    private static final String HASH_MARKER = "Snapshot SHA-256: ";

    private final EngineContext context;

    public PushService(EngineContext context) {
        this.context = context;
    }

    /**
     * Result of a publish attempt.
     *
     * @param published false when the snapshot was identical to the latest release
     */
    public record PushOutcome(boolean published, int version, int fileCount, long totalBytes, SnapshotDiff diff) {
    }

    /**
     * Builds and publishes a snapshot.
     *
     * @throws NexoSyncException when any stage fails; nothing is published in that case
     */
    public PushOutcome push(String operationId) throws NexoSyncException {
        long started = System.currentTimeMillis();
        Path workDirectory = context.workDirectory().resolve("push-" + operationId);

        context.discord().send(operationId, DiscordEvent.PUSH_START,
                new DiscordReport("Nexo Snapshot Publish Started", DiscordReport.COLOR_INFO)
                        .fieldIf(context.discord().include().serverName(), "Server", context.discord().serverName()));

        try {
            validateConfiguration();
            GitHubClient.RepositoryInfo repository = validateAuthentication(operationId);

            // --- Collect --------------------------------------------------------------------
            context.log().progress(operationId, "Scanning " + context.nexoDirectory() + "...");
            SnapshotService.Collected collected = context.snapshots().collect(operationId);
            context.log().progress(operationId, "Collected " + collected.fileCount() + " file(s), "
                    + FileUtils.humanReadableSize(collected.totalBytes()) + ".");

            // --- Compare --------------------------------------------------------------------
            Optional<GitHubRelease> latest = context.github().findLatestSnapshot(operationId);
            if (context.config().versioning().skipIdenticalSnapshot() && latest.isPresent()) {
                Optional<String> remoteHash = resolveRemoteSnapshotHash(operationId, latest.get());
                if (remoteHash.isPresent() && remoteHash.get().equalsIgnoreCase(collected.snapshotHash())) {
                    context.log().info(operationId, "No changes detected; v" + latest.get().snapshotVersion()
                            + " already matches the local content. No release created.");
                    return new PushOutcome(false, latest.get().snapshotVersion(),
                            collected.fileCount(), collected.totalBytes(), null);
                }
            }

            // --- Version --------------------------------------------------------------------
            int nextVersion = determineNextVersion(operationId);
            String tag = context.config().versioning().resolveTag(nextVersion);

            if (context.config().versioning().refuseExistingTag()
                    && context.github().findReleaseByTag(operationId, tag).isPresent()) {
                throw new NexoSyncException(OperationStage.GITHUB_API,
                        "Release tag " + tag + " already exists. NexoSync never overwrites a published release.");
            }

            // --- Package --------------------------------------------------------------------
            SnapshotManifest manifest = context.snapshots().buildManifest(nextVersion, collected, metadata());
            String assetName = context.config().github().release().asset().resolveName(nextVersion);

            Path archive;
            try {
                FileUtils.deleteRecursively(workDirectory);
                archive = context.snapshots().packageSnapshot(workDirectory, assetName, collected, manifest);
            } catch (IOException packagingFailed) {
                throw new NexoSyncException(OperationStage.ARCHIVE,
                        "Unable to prepare the package directory: " + packagingFailed.getMessage(), packagingFailed);
            }

            long archiveSize = sizeOf(archive);
            context.log().progress(operationId, "Packaged " + assetName + " ("
                    + FileUtils.humanReadableSize(archiveSize) + ").");

            // --- Publish --------------------------------------------------------------------
            SnapshotDiff diff = SnapshotDiff.between(
                    latest.flatMap(release -> context.state().loadInstalledManifest(release.snapshotVersion()))
                            .orElse(null),
                    manifest);

            GitHubRelease release = context.github().createRelease(operationId, tag,
                    context.config().versioning().resolveName(nextVersion), buildReleaseBody(manifest, repository));
            context.log().progress(operationId, "Created release " + tag + ".");

            try {
                GitHubAsset uploaded = context.github().uploadAsset(operationId, release, archive, assetName,
                        context.config().github().release().asset().contentType());

                if (uploaded.size() > 0 && uploaded.size() != archiveSize) {
                    throw new NexoSyncException(OperationStage.GITHUB_API,
                            "GitHub stored " + uploaded.size() + " bytes but the package is " + archiveSize + " bytes.");
                }
                context.log().progress(operationId, "Uploaded " + uploaded.name() + " to " + tag + ".");

            } catch (NexoSyncException uploadFailed) {
                // A release without its asset would be seen as the latest snapshot by every receiver.
                context.log().error(operationId, "Asset upload failed, removing the incomplete release.", uploadFailed);
                context.github().deleteRelease(operationId, release.id());
                throw uploadFailed;

            } catch (RuntimeException uploadCrashed) {
                // The same cleanup has to happen for a programming error, not just for a handled
                // failure - otherwise a bug leaves an orphaned release behind on GitHub forever.
                context.log().error(operationId, "Asset upload failed unexpectedly, removing the incomplete release.",
                        uploadCrashed);
                context.github().deleteRelease(operationId, release.id());
                throw new NexoSyncException(OperationStage.GITHUB_API,
                        "The snapshot package could not be uploaded: " + uploadCrashed, uploadCrashed);
            }

            // --- Record ---------------------------------------------------------------------
            context.state().completeTransaction(nextVersion, manifest.snapshotSha256);
            context.state().storeInstalledManifest(manifest);
            context.github().invalidateCache();

            long duration = System.currentTimeMillis() - started;
            context.log().info(operationId, "Published snapshot v" + nextVersion + " - "
                    + manifest.fileCount + " files, " + FileUtils.humanReadableSize(archiveSize) + ", "
                    + String.format(java.util.Locale.ROOT, "%.2fs", duration / 1000.0)
                    + (diff.isEmpty() ? "." : " (" + diff.summary() + ")."));
            reportSuccess(operationId, manifest, diff, archiveSize, duration);

            return new PushOutcome(true, nextVersion, collected.fileCount(), collected.totalBytes(), diff);

        } catch (NexoSyncException failure) {
            reportFailure(operationId, failure);
            throw failure;

        } finally {
            // The packaged archive can be hundreds of megabytes; a failed push must not leave it
            // behind in the cache directory.
            cleanup(workDirectory);
        }
    }

    // -----------------------------------------------------------------------------------------
    // Validation
    // -----------------------------------------------------------------------------------------

    private void validateConfiguration() throws NexoSyncException {
        if (!context.config().sync().mode().canPush()) {
            throw new NexoSyncException(OperationStage.CONFIGURATION,
                    "This server runs in " + context.config().sync().mode() + " mode and may not publish snapshots.");
        }
        if (!context.config().github().isConfigured()) {
            throw new NexoSyncException(OperationStage.CONFIGURATION,
                    "github.owner and github.repository must be configured before publishing.");
        }
        if (!context.config().github().hasToken()) {
            throw new NexoSyncException(OperationStage.GITHUB_AUTH,
                    "Publishing requires a GitHub token with Contents: Read and write.");
        }
        if (!Files.isDirectory(context.nexoDirectory())) {
            throw new NexoSyncException(OperationStage.CONFIGURATION,
                    "The configured Nexo directory does not exist: " + context.nexoDirectory());
        }
    }

    private GitHubClient.RepositoryInfo validateAuthentication(String operationId) throws NexoSyncException {
        GitHubClient.RepositoryInfo repository = context.github().repository(operationId);
        if (!repository.canPush()) {
            throw new NexoSyncException(OperationStage.GITHUB_AUTH,
                    "The configured token cannot write to " + repository.fullName()
                            + ". Grant Contents: Read and write.");
        }
        context.log().debug(operationId, "Authenticated against " + repository.fullName()
                + (repository.isPrivate() ? " (private)" : " (public)") + ".");
        return repository;
    }

    // -----------------------------------------------------------------------------------------
    // Versioning
    // -----------------------------------------------------------------------------------------

    private int determineNextVersion(String operationId) throws NexoSyncException {
        int highestRemote = context.github().highestKnownVersion(operationId);
        int localInstalled = context.state().current().installedSnapshotOrZero();
        int highest = Math.max(highestRemote, localInstalled);

        if (highest <= 0) {
            return context.config().versioning().initialVersion();
        }
        return highest + 1;
    }

    /**
     * Reads the content hash of a published snapshot.
     *
     * <p>The hash is recorded in the release body when NexoSync publishes, which makes the
     * "no changes" comparison a single metadata request. When the marker is missing - a release
     * published by an older build, or edited by hand - the asset is downloaded and its manifest is
     * read instead.</p>
     */
    private Optional<String> resolveRemoteSnapshotHash(String operationId, GitHubRelease release) {
        Optional<String> marker = extractHash(release.body());
        if (marker.isPresent()) {
            return marker;
        }

        String assetName = context.config().github().release().asset().resolveName(release.snapshotVersion());
        Optional<GitHubAsset> asset = release.findSnapshotAsset(assetName);
        if (asset.isEmpty()) {
            return Optional.empty();
        }

        Path temporary = context.workDirectory().resolve("compare").resolve(asset.get().name());
        try {
            context.log().debug(operationId, "Release body carries no content hash; downloading v"
                    + release.snapshotVersion() + " to compare.");
            context.github().downloadAsset(operationId, asset.get(), temporary);
            SnapshotManifest manifest = context.snapshots().readManifest(temporary);
            return Optional.ofNullable(manifest.snapshotSha256);
        } catch (NexoSyncException comparisonFailed) {
            context.log().warn(operationId, "Unable to compare against the latest release: "
                    + comparisonFailed.getMessage());
            return Optional.empty();
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // Temporary file only.
            }
        }
    }

    private static Optional<String> extractHash(String body) {
        if (body == null) {
            return Optional.empty();
        }
        int index = body.indexOf(HASH_MARKER);
        if (index < 0) {
            return Optional.empty();
        }
        String remainder = body.substring(index + HASH_MARKER.length()).trim();
        StringBuilder hash = new StringBuilder();
        for (int i = 0; i < remainder.length() && hash.length() < 64; i++) {
            char character = remainder.charAt(i);
            if (Character.digit(character, 16) >= 0) {
                hash.append(character);
            } else if (character != '`') {
                break;
            }
        }
        return hash.length() == 64 ? Optional.of(hash.toString()) : Optional.empty();
    }

    // -----------------------------------------------------------------------------------------
    // Release content
    // -----------------------------------------------------------------------------------------

    private SnapshotService.Metadata metadata() {
        String publisher = context.discord().serverName();
        return new SnapshotService.Metadata(
                publisher,
                context.pluginVersion(),
                context.nexo().version(),
                Bukkit.getMinecraftVersion(),
                context.scheduler().platformName());
    }

    private String buildReleaseBody(SnapshotManifest manifest, GitHubClient.RepositoryInfo repository) {
        StringBuilder body = new StringBuilder();
        body.append("Nexo snapshot published by NexoSync.\n\n");
        body.append("- Snapshot version: v").append(manifest.snapshotVersion).append('\n');
        body.append("- Files: ").append(manifest.fileCount).append('\n');
        body.append("- Total size: ").append(FileUtils.humanReadableSize(manifest.totalSize())).append('\n');
        body.append("- Managed paths: `").append(String.join("`, `", manifest.managedPaths)).append("`\n");
        body.append("- Published from: ").append(manifest.publisher).append('\n');
        body.append("- Platform: ").append(manifest.platform).append(' ').append(manifest.serverVersion).append('\n');
        body.append("- Nexo: ").append(manifest.nexoVersion).append('\n');
        body.append("- Repository: ").append(repository.fullName()).append("\n\n");
        body.append(HASH_MARKER).append('`').append(manifest.snapshotSha256).append("`\n");
        return body.toString();
    }

    // -----------------------------------------------------------------------------------------
    // Reporting
    // -----------------------------------------------------------------------------------------

    private void reportSuccess(String operationId, SnapshotManifest manifest, SnapshotDiff diff, long archiveSize,
                               long durationMillis) {

        var include = context.discord().include();
        DiscordReport report = new DiscordReport("Nexo Snapshot Published", DiscordReport.COLOR_SUCCESS)
                .fieldIf(include.serverName(), "Server", context.discord().serverName())
                .fieldIf(include.snapshotVersion(), "Snapshot", "v" + manifest.snapshotVersion)
                .fieldIf(include.fileCount(), "Files", Integer.toString(manifest.fileCount))
                .field("Package Size", FileUtils.humanReadableSize(archiveSize))
                .fieldIf(include.duration(), "Duration",
                        String.format(java.util.Locale.ROOT, "%.2fs", durationMillis / 1000.0));

        if (include.changedFiles() && diff != null && !diff.isEmpty()) {
            report.blockIf(true, "Changes", diff.summary() + "\n```\n"
                    + String.join("\n", diff.preview(15)) + "\n```");
        }
        context.discord().send(operationId, DiscordEvent.PUSH_SUCCESS, report);
    }

    private void reportFailure(String operationId, NexoSyncException failure) {
        var include = context.discord().include();
        DiscordReport report = new DiscordReport("Nexo Snapshot Publish Failed", DiscordReport.COLOR_FAILURE)
                .fieldIf(include.serverName(), "Server", context.discord().serverName())
                .field("Failed Stage", failure.stage().display());

        if (include.errorDetails()) {
            report.blockIf(true, "Reason", "```\n" + failure.getMessage() + "\n```");
        }
        context.discord().send(operationId, DiscordEvent.PUSH_FAILURE, report);
    }

    // -----------------------------------------------------------------------------------------
    // Housekeeping
    // -----------------------------------------------------------------------------------------

    private long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException unreadable) {
            return -1L;
        }
    }

    private void cleanup(Path workDirectory) {
        try {
            FileUtils.deleteRecursively(workDirectory);
        } catch (IOException ignored) {
            // The cache directory is cleaned on the next start.
        }
    }
}
