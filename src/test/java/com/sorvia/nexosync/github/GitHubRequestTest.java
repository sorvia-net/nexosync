package com.sorvia.nexosync.github;

import com.sorvia.nexosync.config.NexoSyncConfig;
import com.sorvia.nexosync.config.SyncMode;
import com.sorvia.nexosync.log.NexoSyncLogger;
import com.sorvia.nexosync.util.SecretMask;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers request construction against the shipped {@code config.yml}.
 *
 * <p>An {@link java.net.http.HttpRequest} can be built and inspected without ever reaching the
 * network, which makes the header rules testable. That matters because {@code Builder.header}
 * <em>appends</em> while {@code setHeader} replaces — a distinction that silently turned asset
 * downloads into metadata downloads until it was found on a live server.</p>
 *
 * <p>Loading the real {@code config.yml} here doubles as a drift check: if a key is renamed in the
 * resource but not in {@link NexoSyncConfig}, the defaults asserted below stop matching.</p>
 */
class GitHubRequestTest {

    private static final String TOKEN = "github_pat_11TESTTOKEN0123456789abcdef";
    private static final String TOKEN_VARIABLE = "NEXOSYNC_GITHUB_TOKEN";

    private static NexoSyncConfig config;
    private static GitHubClient client;
    private static String previousTokenProperty;

    @BeforeAll
    static void loadShippedConfiguration() throws IOException {
        // Secrets.expand falls back to system properties, which lets the test resolve the
        // ${NEXOSYNC_GITHUB_TOKEN} reference the shipped file uses without touching the environment.
        previousTokenProperty = System.getProperty(TOKEN_VARIABLE);
        System.setProperty(TOKEN_VARIABLE, TOKEN);

        try (InputStream in = GitHubRequestTest.class.getClassLoader().getResourceAsStream("config.yml")) {
            assertNotNull(in, "config.yml is missing from src/main/resources");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            config = NexoSyncConfig.load(yaml);
        }
        client = new GitHubClient(config, new NexoSyncLogger(Logger.getLogger("NexoSyncTest"), new SecretMask()), "1.0.0");
    }

    @AfterAll
    static void restoreProperty() {
        if (previousTokenProperty == null) {
            System.clearProperty(TOKEN_VARIABLE);
        } else {
            System.setProperty(TOKEN_VARIABLE, previousTokenProperty);
        }
    }

    // -------------------------------------------------------------------------------------------
    // Download requests
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("A download asks for raw bytes and nothing else")
    void downloadAcceptsOnlyBinary() {
        var request = client.buildDownloadRequest(
                URI.create("https://api.github.com/repos/o/r/releases/assets/1"), true, Duration.ofSeconds(60));

        List<String> accept = request.headers().allValues("Accept");

        assertEquals(1, accept.size(), () -> "exactly one Accept header is allowed, found " + accept);
        assertEquals("application/octet-stream", accept.get(0));
        assertFalse(accept.contains("application/vnd.github+json"),
                "asking for JSON as well makes GitHub return asset metadata instead of the file");
    }

    @Test
    @DisplayName("A download identifies itself exactly once")
    void downloadSendsOneUserAgent() {
        var request = client.buildDownloadRequest(
                URI.create("https://api.github.com/repos/o/r/releases/assets/1"), true, Duration.ofSeconds(60));

        assertEquals(1, request.headers().allValues("User-Agent").size());
        assertTrue(request.headers().firstValue("User-Agent").orElse("").startsWith("NexoSync/1.0.0"));
        assertEquals("GET", request.method());
        assertTrue(request.timeout().isPresent(), "a download must not be able to hang forever");
    }

    @Test
    @DisplayName("A download authenticates while it is still talking to the API host")
    void downloadSendsTokenOnApiHost() {
        var request = client.buildDownloadRequest(
                URI.create("https://api.github.com/repos/o/r/releases/assets/1"), true, Duration.ofSeconds(60));

        assertEquals("Bearer " + TOKEN, request.headers().firstValue("Authorization").orElse(null));
    }

    @Test
    @DisplayName("A download drops the token once it has been redirected off the API host")
    void downloadDropsTokenAfterRedirect() {
        var request = client.buildDownloadRequest(
                URI.create("https://objects.githubusercontent.com/some/signed/url"), false, Duration.ofSeconds(60));

        assertTrue(request.headers().firstValue("Authorization").isEmpty(),
                "object storage rejects a request that carries a GitHub token as well as its own signature");
        assertTrue(request.headers().firstValue("X-GitHub-Api-Version").isEmpty());
        assertEquals(List.of("application/octet-stream"), request.headers().allValues("Accept"));
    }

    // -------------------------------------------------------------------------------------------
    // Shipped configuration
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("The shipped config.yml still maps onto the configuration model")
    void shippedDefaults() {
        assertEquals("en_US", config.plugin().language());
        assertFalse(config.plugin().debug());

        assertEquals(List.of("glyphs", "items", "pack/external_packs"), config.sync().paths());
        assertEquals(SyncMode.BOTH, config.sync().mode());
        assertEquals("plugins/Nexo", config.sync().nexoDirectory());
        assertTrue(config.sync().requireNexoDirectory());

        assertEquals("nexosync-v", config.github().release().tagPrefix());
        assertEquals("nexo-snapshot-v42.zip", config.github().release().asset().resolveName(42));
        assertEquals("nexosync-v42", config.versioning().resolveTag(42));
        assertFalse(config.github().release().allowDrafts());
        assertFalse(config.github().release().allowPrereleases());

        assertEquals(60, config.update().checkIntervalSeconds());
        assertTrue(config.update().autoUpdate());
        assertFalse(config.update().allowDowngrade());

        assertTrue(config.backup().enabled());
        assertTrue(config.backup().backupBeforeApply());
        assertEquals(5, config.backup().keepLast());

        assertEquals(10_000, config.safety().maxFileCount());
        assertTrue(config.safety().rejectPathTraversal());
        assertTrue(config.safety().rejectSymbolicLinks());
        assertTrue(config.safety().strictManagedPaths());

        assertFalse(config.discord().enabled(), "the webhook integration ships disabled");
    }

    @Test
    @DisplayName("The shipped placeholders are reported as problems rather than used as a repository")
    void placeholdersAreRejected() {
        assertFalse(config.github().isConfigured());
        assertTrue(config.problems().stream().anyMatch(problem -> problem.contains("placeholder")),
                () -> "expected a placeholder problem, got " + config.problems());
    }

    @Test
    @DisplayName("An environment reference in the shipped file resolves to the real value")
    void tokenReferenceResolves() {
        assertTrue(config.github().hasToken());
        assertEquals(TOKEN, config.github().token());
        assertFalse(config.github().tokenReferenceUnresolved());
    }
}
