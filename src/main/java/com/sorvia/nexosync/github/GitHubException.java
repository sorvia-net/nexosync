package com.sorvia.nexosync.github;

import com.sorvia.nexosync.NexoSyncException;
import com.sorvia.nexosync.engine.OperationStage;

/**
 * A GitHub request that failed, classified well enough to produce a useful message.
 *
 * <p>The language key lets the command layer print a translated explanation ("authentication
 * failed", "rate limit reached") instead of an HTTP status code, while the status code itself stays
 * available for the log file.</p>
 */
public final class GitHubException extends NexoSyncException {

    private final int status;
    private final String languageKey;
    private final boolean retryable;

    public GitHubException(OperationStage stage, int status, String languageKey, String message, boolean retryable) {
        super(stage, message);
        this.status = status;
        this.languageKey = languageKey;
        this.retryable = retryable;
    }

    public GitHubException(OperationStage stage, int status, String languageKey, String message, Throwable cause,
                           boolean retryable) {
        super(stage, message, cause);
        this.status = status;
        this.languageKey = languageKey;
        this.retryable = retryable;
    }

    public int status() {
        return status;
    }

    public String languageKey() {
        return languageKey;
    }

    public boolean retryable() {
        return retryable;
    }

    // -----------------------------------------------------------------------------------------
    // Factories
    // -----------------------------------------------------------------------------------------

    /**
     * Classifies an HTTP status into the right stage, language key and retry decision.
     *
     * <p>Authentication and authorisation failures are never retried: repeating them cannot change
     * the outcome and only burns the rate limit.</p>
     */
    public static GitHubException fromStatus(OperationStage stage, int status, String body, boolean rateLimited) {
        String detail = summarise(body);

        if (rateLimited || status == 429) {
            return new GitHubException(stage, status, "github.rate-limit",
                    "GitHub API rate limit reached." + detail, true);
        }
        return switch (status) {
            case 401 -> new GitHubException(OperationStage.GITHUB_AUTH, status, "github.unauthorized",
                    "GitHub rejected the configured token." + detail, false);
            case 403 -> new GitHubException(OperationStage.GITHUB_AUTH, status, "github.forbidden",
                    "GitHub denied the request; the token is missing the required repository permission." + detail, false);
            case 404 -> new GitHubException(stage, status, "github.not-found",
                    "The configured repository, release or asset was not found." + detail, false);
            case 422 -> {
                // A brand new repository has no commits, and a release has to point at one. This is
                // the first thing a new installation hits, so it gets its own explanation instead of
                // GitHub's raw validation payload.
                if (body != null && body.contains("Repository is empty")) {
                    yield new GitHubException(stage, status, "github.repository-empty",
                            "The repository has no commits yet, so GitHub will not create a release in it. "
                                    + "Add any file to the repository - a README is enough - and publish again.",
                            false);
                }
                yield new GitHubException(stage, status, "github.unknown-error",
                        "GitHub rejected the request as invalid." + detail, false);
            }
            case 408 -> new GitHubException(stage, status, "github.timeout",
                    "GitHub reported a request timeout." + detail, true);
            default -> {
                boolean serverError = status >= 500;
                yield new GitHubException(stage, status,
                        serverError ? "github.server-error" : "github.unknown-error",
                        "GitHub returned HTTP " + status + "." + detail, serverError);
            }
        };
    }

    private static String summarise(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String trimmed = body.strip().replaceAll("\\s+", " ");
        if (trimmed.length() > 300) {
            trimmed = trimmed.substring(0, 300) + "...";
        }
        return " Response: " + trimmed;
    }
}
