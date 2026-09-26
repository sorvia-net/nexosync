package com.sorvia.nexosync.github;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sorvia.nexosync.NexoSyncException;
import com.sorvia.nexosync.config.NexoSyncConfig;
import com.sorvia.nexosync.engine.OperationStage;
import com.sorvia.nexosync.log.NexoSyncLogger;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The GitHub REST client.
 *
 * <p>Only the six release operations NexoSync actually needs are implemented. Three behaviours are
 * worth calling out:</p>
 *
 * <ul>
 *   <li><b>Retries are selective.</b> Transient statuses (408, 429, 5xx) and connection failures are
 *       retried with a delay; authentication and validation failures are not, because repeating them
 *       cannot succeed and only consumes the rate limit.</li>
 *   <li><b>The release list is cached with an ETag.</b> A periodic check that finds nothing new
 *       costs a conditional request that GitHub answers with 304 and does not count against the
 *       primary rate limit.</li>
 *   <li><b>Asset downloads follow redirects manually.</b> GitHub redirects to object storage, which
 *       rejects a request carrying a GitHub authorisation header, so the header is dropped as soon
 *       as the request leaves the API host.</li>
 * </ul>
 */
public final class GitHubClient {

    private static final String ACCEPT_JSON = "application/vnd.github+json";
    private static final String ACCEPT_BINARY = "application/octet-stream";
    private static final int MAX_REDIRECTS = 5;

    private final NexoSyncConfig.GitHubSection config;
    private final NexoSyncLogger log;
    private final String userAgent;

    private final HttpClient apiClient;
    private final HttpClient downloadClient;

    private final AtomicReference<CachedReleases> releaseCache = new AtomicReference<>(null);

    public GitHubClient(NexoSyncConfig config, NexoSyncLogger log, String pluginVersion) {
        this.config = config.github();
        this.log = log;
        this.userAgent = "NexoSync/" + pluginVersion + " (Sorvia Development Solutions)";

        Duration connectTimeout = Duration.ofSeconds(this.config.connection().connectTimeoutSeconds());
        this.apiClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.downloadClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Cached release listing keyed by the ETag GitHub returned with it.
     */
    private record CachedReleases(String etag, List<GitHubRelease> releases) {
    }

    public void invalidateCache() {
        releaseCache.set(null);
    }

    // -----------------------------------------------------------------------------------------
    // Repository
    // -----------------------------------------------------------------------------------------

    /**
     * Repository metadata used by {@code /nexosync status} and by the startup connectivity check.
     */
    public record RepositoryInfo(String fullName, boolean isPrivate, boolean canPush) {
    }

    public RepositoryInfo repository(String operationId) throws NexoSyncException {
        HttpResponse<String> response = sendJson(operationId, OperationStage.GITHUB_API,
                HttpRequest.newBuilder(URI.create(repositoryUrl())).GET());

        JsonObject json = parseObject(response.body(), OperationStage.GITHUB_API);
        boolean canPush = false;
        if (json.has("permissions") && json.get("permissions").isJsonObject()) {
            JsonObject permissions = json.getAsJsonObject("permissions");
            canPush = permissions.has("push") && permissions.get("push").getAsBoolean();
        }
        return new RepositoryInfo(
                json.has("full_name") ? json.get("full_name").getAsString() : repositorySlug(),
                json.has("private") && json.get("private").getAsBoolean(),
                canPush);
    }

    // -----------------------------------------------------------------------------------------
    // Releases
    // -----------------------------------------------------------------------------------------

    /**
     * Lists the most recent releases, using a conditional request when a cached copy exists.
     */
    public List<GitHubRelease> listReleases(String operationId) throws NexoSyncException {
        CachedReleases cached = releaseCache.get();

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(repositoryUrl() + "/releases?per_page=50")).GET();
        if (cached != null && cached.etag() != null && !cached.etag().isBlank()) {
            builder.setHeader("If-None-Match", cached.etag());
        }

        HttpResponse<String> response = sendJson(operationId, OperationStage.GITHUB_API, builder, 304);

        if (response.statusCode() == 304) {
            log.debug(operationId, "Release metadata unchanged (HTTP 304), using the cached listing.");
            return cached == null ? List.of() : cached.releases();
        }

        JsonArray array;
        try {
            array = JsonParser.parseString(response.body()).getAsJsonArray();
        } catch (RuntimeException malformed) {
            throw new GitHubException(OperationStage.GITHUB_API, response.statusCode(), "github.unknown-error",
                    "GitHub returned a release listing that could not be parsed.", malformed, false);
        }

        List<GitHubRelease> releases = new ArrayList<>();
        for (JsonElement element : array) {
            if (element.isJsonObject()) {
                releases.add(GitHubRelease.from(element.getAsJsonObject(), config.release().tagPrefix()));
            }
        }

        List<GitHubRelease> immutable = List.copyOf(releases);
        response.headers().firstValue("etag")
                .ifPresentOrElse(etag -> releaseCache.set(new CachedReleases(etag, immutable)),
                        () -> releaseCache.set(new CachedReleases(null, immutable)));
        return immutable;
    }

    /**
     * Returns the newest release that this server is allowed to install.
     *
     * <p>Drafts and prereleases are excluded unless explicitly enabled, and a release without a
     * usable snapshot asset is skipped rather than reported as the latest version.</p>
     */
    public Optional<GitHubRelease> findLatestSnapshot(String operationId) throws NexoSyncException {
        GitHubRelease best = null;
        for (GitHubRelease release : listReleases(operationId)) {
            if (!isEligible(release, operationId)) {
                continue;
            }
            if (best == null || release.snapshotVersion() > best.snapshotVersion()) {
                best = release;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Returns the highest snapshot version present in the repository, including drafts and
     * prereleases. Publishing uses this so a new release never reuses an existing number.
     */
    public int highestKnownVersion(String operationId) throws NexoSyncException {
        int highest = 0;
        for (GitHubRelease release : listReleases(operationId)) {
            if (release.isNexoSyncRelease()) {
                highest = Math.max(highest, release.snapshotVersion());
            }
        }
        return highest;
    }

    public Optional<GitHubRelease> findSnapshot(String operationId, int version) throws NexoSyncException {
        for (GitHubRelease release : listReleases(operationId)) {
            if (release.snapshotVersion() == version) {
                return Optional.of(release);
            }
        }
        return Optional.empty();
    }

    public Optional<GitHubRelease> findReleaseByTag(String operationId, String tag) throws NexoSyncException {
        String url = repositoryUrl() + "/releases/tags/" + URLEncoder.encode(tag, StandardCharsets.UTF_8);
        HttpResponse<String> response = sendJson(operationId, OperationStage.GITHUB_API,
                HttpRequest.newBuilder(URI.create(url)).GET(), 404);

        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        return Optional.of(GitHubRelease.from(
                parseObject(response.body(), OperationStage.GITHUB_API), config.release().tagPrefix()));
    }

    public GitHubRelease createRelease(String operationId, String tag, String name, String body)
            throws NexoSyncException {

        JsonObject payload = new JsonObject();
        payload.addProperty("tag_name", tag);
        payload.addProperty("name", name);
        payload.addProperty("body", body);
        payload.addProperty("draft", false);
        payload.addProperty("prerelease", false);

        HttpResponse<String> response = sendJson(operationId, OperationStage.GITHUB_API,
                HttpRequest.newBuilder(URI.create(repositoryUrl() + "/releases"))
                        .setHeader("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8)));

        invalidateCache();
        return GitHubRelease.from(parseObject(response.body(), OperationStage.GITHUB_API), config.release().tagPrefix());
    }

    /**
     * Removes a release. Used to clean up after a failed asset upload so a half-published release
     * never becomes the "latest snapshot" for other servers.
     */
    public void deleteRelease(String operationId, long releaseId) {
        try {
            sendJson(operationId, OperationStage.GITHUB_API,
                    HttpRequest.newBuilder(URI.create(repositoryUrl() + "/releases/" + releaseId)).DELETE(), 404);
            invalidateCache();
        } catch (NexoSyncException cleanupFailed) {
            log.warn(operationId, "Unable to remove the incomplete release: " + cleanupFailed.getMessage());
        }
    }

    public GitHubAsset uploadAsset(String operationId, GitHubRelease release, Path file, String assetName,
                                   String contentType) throws NexoSyncException {

        HttpRequest.Builder builder;
        try {
            // Content-Length is a restricted header: HttpClient refuses to let a caller set it and
            // derives it from the body publisher instead. Setting it throws IllegalArgumentException.
            builder = HttpRequest.newBuilder(URI.create(release.resolveUploadUrl(assetName)))
                    .setHeader("Content-Type", contentType)
                    .POST(HttpRequest.BodyPublishers.ofFile(file));
        } catch (IOException unreadable) {
            throw new NexoSyncException(OperationStage.GITHUB_API,
                    "Unable to open the snapshot package for upload: " + unreadable.getMessage(), unreadable);
        }

        log.debug(operationId, "Uploading " + assetName + " (" + sizeOrUnknown(file) + ") to " + release.tagName() + ".");

        HttpResponse<String> response = sendJson(operationId, OperationStage.GITHUB_API, builder,
                Duration.ofSeconds(config.connection().downloadTimeoutSeconds()));

        invalidateCache();
        return GitHubAsset.from(parseObject(response.body(), OperationStage.GITHUB_API));
    }

    /**
     * Downloads a release asset to a local file.
     *
     * <p>The file is written to a {@code .part} sibling and moved into place only after the transfer
     * completes, so an interrupted download can never be mistaken for a finished one.</p>
     */
    public Path downloadAsset(String operationId, GitHubAsset asset, Path target) throws NexoSyncException {
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        try {
            Files.createDirectories(target.getParent());
            Files.deleteIfExists(partial);
        } catch (IOException preparationFailed) {
            throw new NexoSyncException(OperationStage.DOWNLOAD,
                    "Unable to prepare the download directory: " + preparationFailed.getMessage(), preparationFailed);
        }

        URI uri = URI.create(asset.apiUrl().isBlank() ? asset.browserUrl() : asset.apiUrl());
        boolean sendAuthorization = true;
        Duration timeout = Duration.ofSeconds(config.connection().downloadTimeoutSeconds());

        try {
            for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
                HttpRequest request = buildDownloadRequest(uri, sendAuthorization, timeout);

                HttpResponse<Path> response = executeWithRetry(operationId, OperationStage.DOWNLOAD, request,
                        HttpResponse.BodyHandlers.ofFile(partial), downloadClient);

                int status = response.statusCode();
                if (status >= 300 && status < 400) {
                    Optional<String> location = response.headers().firstValue("location");
                    if (location.isEmpty()) {
                        throw new GitHubException(OperationStage.DOWNLOAD, status, "github.unknown-error",
                                "GitHub returned a redirect without a target.", false);
                    }
                    URI next = uri.resolve(location.get());
                    // Object storage rejects requests that also carry a GitHub token.
                    sendAuthorization = next.getHost() != null && next.getHost().equalsIgnoreCase(uri.getHost());
                    uri = next;
                    continue;
                }
                if (status != 200) {
                    String body = readSmall(partial);
                    throw GitHubException.fromStatus(OperationStage.DOWNLOAD, status, body, false);
                }

                long downloaded = Files.size(partial);
                if (asset.size() > 0 && downloaded != asset.size()) {
                    throw new NexoSyncException(OperationStage.DOWNLOAD,
                            "Downloaded " + downloaded + " bytes but the release asset is " + asset.size() + " bytes.");
                }

                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
                return target;
            }
            throw new NexoSyncException(OperationStage.DOWNLOAD, "Too many redirects while downloading the snapshot.");
        } catch (IOException transferFailed) {
            throw new NexoSyncException(OperationStage.DOWNLOAD,
                    "The snapshot download failed: " + transferFailed.getMessage(), transferFailed);
        } finally {
            try {
                Files.deleteIfExists(partial);
            } catch (IOException ignored) {
                // A leftover partial file is harmless; it is overwritten on the next attempt.
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Eligibility
    // -----------------------------------------------------------------------------------------

    private boolean isEligible(GitHubRelease release, String operationId) {
        if (!release.isNexoSyncRelease()) {
            return false;
        }
        if (release.draft() && !config.release().allowDrafts()) {
            log.debug(operationId, "Skipping draft release " + release.tagName() + ".");
            return false;
        }
        if (release.prerelease() && !config.release().allowPrereleases()) {
            log.debug(operationId, "Skipping prerelease " + release.tagName() + ".");
            return false;
        }
        if (release.findSnapshotAsset(config.release().asset().resolveName(release.snapshotVersion())).isEmpty()) {
            log.debug(operationId, "Release " + release.tagName() + " has no usable snapshot asset.");
            return false;
        }
        return true;
    }

    // -----------------------------------------------------------------------------------------
    // Transport
    // -----------------------------------------------------------------------------------------

    private String repositorySlug() {
        return config.owner() + "/" + config.repository();
    }

    private String repositoryUrl() {
        return config.apiBaseUrl() + "/repos/"
                + URLEncoder.encode(config.owner(), StandardCharsets.UTF_8) + "/"
                + URLEncoder.encode(config.repository(), StandardCharsets.UTF_8);
    }

    /**
     * Applies the standard JSON API headers.
     *
     * <p>{@code setHeader} rather than {@code header}: the latter <em>appends</em>, and a request
     * that carries two {@code Accept} values gets whichever one the server prefers. That is not a
     * theoretical concern - it is what made asset downloads return metadata instead of the file.</p>
     */
    private void applyApiHeaders(HttpRequest.Builder builder) {
        builder.setHeader("Accept", ACCEPT_JSON)
                .setHeader("X-GitHub-Api-Version", config.apiVersion())
                .setHeader("User-Agent", userAgent);
        if (config.hasToken()) {
            builder.setHeader("Authorization", "Bearer " + config.token());
        }
    }

    /**
     * Builds an asset download request.
     *
     * <p>Kept separate from {@link #applyApiHeaders} because the two need opposite {@code Accept}
     * values: this one must ask for the raw bytes. The authorisation header is dropped once the
     * request has been redirected off the API host, since object storage rejects a request that
     * carries a GitHub token as well as its own signed URL parameters.</p>
     */
    HttpRequest buildDownloadRequest(URI uri, boolean sendAuthorization, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .GET()
                .setHeader("Accept", ACCEPT_BINARY)
                .setHeader("User-Agent", userAgent);

        if (sendAuthorization) {
            builder.setHeader("X-GitHub-Api-Version", config.apiVersion());
            if (config.hasToken()) {
                builder.setHeader("Authorization", "Bearer " + config.token());
            }
        }
        return builder.build();
    }

    private HttpResponse<String> sendJson(String operationId, OperationStage stage, HttpRequest.Builder builder,
                                          int... acceptedStatuses) throws NexoSyncException {
        return sendJson(operationId, stage, builder,
                Duration.ofSeconds(config.connection().requestTimeoutSeconds()), acceptedStatuses);
    }

    private HttpResponse<String> sendJson(String operationId, OperationStage stage, HttpRequest.Builder builder,
                                          Duration timeout, int... acceptedStatuses) throws NexoSyncException {

        applyApiHeaders(builder);
        builder.timeout(timeout);

        HttpResponse<String> response = executeWithRetry(operationId, stage, builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), apiClient);

        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return response;
        }
        for (int accepted : acceptedStatuses) {
            if (status == accepted) {
                return response;
            }
        }
        throw GitHubException.fromStatus(stage, status, response.body(), isRateLimited(response));
    }

    /**
     * Sends a request, retrying only what is worth retrying.
     */
    private <T> HttpResponse<T> executeWithRetry(String operationId, OperationStage stage, HttpRequest request,
                                                 HttpResponse.BodyHandler<T> handler, HttpClient client)
            throws NexoSyncException {

        int attempts = config.retry().enabled() ? config.retry().maxAttempts() : 1;
        NexoSyncException lastFailure = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpResponse<T> response = client.send(request, handler);
                int status = response.statusCode();

                boolean transientStatus = status == 408 || status == 429 || status >= 500;
                if (!transientStatus || attempt == attempts) {
                    return response;
                }

                long delay = retryDelay(response, attempt);
                log.warn(operationId, "GitHub returned HTTP " + status + ", retrying in " + delay + " ms ("
                        + attempt + "/" + attempts + ").");
                sleep(delay);
                lastFailure = GitHubException.fromStatus(stage, status, null, isRateLimited(response));

            } catch (HttpTimeoutException timeout) {
                lastFailure = new GitHubException(stage, 0, "github.timeout",
                        "The GitHub request timed out.", timeout, true);
                if (attempt == attempts) {
                    break;
                }
                sleep(backoff(attempt));

            } catch (InterruptedIOException interrupted) {
                Thread.currentThread().interrupt();
                throw new NexoSyncException(stage, "The GitHub request was interrupted.", interrupted);

            } catch (IOException connectionFailed) {
                lastFailure = new GitHubException(stage, 0, "github.unknown-error",
                        "Unable to reach GitHub: " + connectionFailed.getMessage(), connectionFailed, true);
                if (attempt == attempts) {
                    break;
                }
                log.warn(operationId, "GitHub request failed (" + connectionFailed.getMessage() + "), retrying ("
                        + attempt + "/" + attempts + ").");
                sleep(backoff(attempt));

            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new NexoSyncException(stage, "The GitHub request was interrupted.", interrupted);
            }
        }

        throw lastFailure != null
                ? lastFailure
                : new GitHubException(stage, 0, "github.unknown-error", "The GitHub request failed.", false);
    }

    /**
     * Honours {@code Retry-After} and {@code x-ratelimit-reset} when present, but never waits longer
     * than a minute: a scheduled check that sleeps for an hour would look like a hung server.
     */
    private long retryDelay(HttpResponse<?> response, int attempt) {
        Optional<String> retryAfter = response.headers().firstValue("retry-after");
        if (retryAfter.isPresent()) {
            try {
                return Math.min(60_000L, Long.parseLong(retryAfter.get().trim()) * 1000L);
            } catch (NumberFormatException notSeconds) {
                // The header may hold an HTTP date; fall through to the configured backoff.
            }
        }
        if (isRateLimited(response)) {
            Optional<String> reset = response.headers().firstValue("x-ratelimit-reset");
            if (reset.isPresent()) {
                try {
                    long resetEpoch = Long.parseLong(reset.get().trim());
                    long waitMillis = (resetEpoch * 1000L) - System.currentTimeMillis();
                    return Math.max(config.retry().delayMilliseconds(), Math.min(60_000L, waitMillis));
                } catch (NumberFormatException malformed) {
                    // Ignored, the configured backoff is used instead.
                }
            }
        }
        return backoff(attempt);
    }

    private long backoff(int attempt) {
        return Math.min(60_000L, config.retry().delayMilliseconds() * (long) attempt);
    }

    private static boolean isRateLimited(HttpResponse<?> response) {
        if (response.statusCode() == 429) {
            return true;
        }
        if (response.statusCode() != 403) {
            return false;
        }
        return response.headers().firstValue("x-ratelimit-remaining")
                .map(value -> value.trim().equals("0"))
                .orElse(false);
    }

    private static void sleep(long millis) throws NexoSyncException {
        try {
            Thread.sleep(Math.max(0L, millis));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new NexoSyncException(OperationStage.GITHUB_API, "Interrupted while waiting to retry.", interrupted);
        }
    }

    private static JsonObject parseObject(String body, OperationStage stage) throws NexoSyncException {
        try {
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException malformed) {
            throw new GitHubException(stage, 0, "github.unknown-error",
                    "GitHub returned a response that could not be parsed.", malformed, false);
        }
    }

    private static String sizeOrUnknown(Path file) {
        try {
            return Files.size(file) + " bytes";
        } catch (IOException unreadable) {
            return "unknown size";
        }
    }

    private static String readSmall(Path file) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > 8192) {
                return "";
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unreadable) {
            return "";
        }
    }

    /**
     * Human-readable summary used by the status command.
     */
    public String describe() {
        return repositorySlug() + " via " + config.apiBaseUrl().toLowerCase(Locale.ROOT);
    }
}
