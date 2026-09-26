package com.sorvia.nexosync;

import com.sorvia.nexosync.config.Secrets;
import com.sorvia.nexosync.config.SyncMode;
import com.sorvia.nexosync.engine.BackupEntry;
import com.sorvia.nexosync.github.GitHubRelease;
import com.sorvia.nexosync.util.SecretMask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers credential handling and the small parsers that decide what NexoSync acts on.
 *
 * <p>Masking is tested from both directions: values NexoSync knows about must disappear, and so must
 * credential-shaped text it has never seen, because the realistic leak is a token quoted back inside
 * someone else's error message.</p>
 */
class SecretHandlingTest {

    @Test
    @DisplayName("A registered secret is removed from every message")
    void registeredSecretsAreMasked() {
        SecretMask mask = new SecretMask();
        mask.register("github_pat_11ABCDEFG0123456789abcdef");

        String masked = mask.apply("Authenticating with github_pat_11ABCDEFG0123456789abcdef against GitHub");

        assertFalse(masked.contains("github_pat_11ABCDEFG0123456789abcdef"));
        assertTrue(masked.contains("[REDACTED]"));
    }

    @Test
    @DisplayName("Credential-shaped text is removed even when it was never registered")
    void unknownSecretsAreMasked() {
        SecretMask mask = new SecretMask();

        assertFalse(mask.apply("token ghp_abcdefghijklmnopqrstuvwxyz012345 rejected")
                .contains("ghp_abcdefghijklmnopqrstuvwxyz012345"));
        assertFalse(mask.apply("POST https://discord.com/api/webhooks/123456/aVerySecretToken")
                .contains("aVerySecretToken"));

        String header = mask.apply("Authorization: Bearer someOpaqueTokenValue");
        assertTrue(header.startsWith("Authorization: "), "the readable prefix is kept");
        assertFalse(header.contains("someOpaqueTokenValue"));
    }

    @Test
    @DisplayName("Short values are never masked, so ordinary text stays readable")
    void shortValuesAreIgnored() {
        SecretMask mask = new SecretMask();
        mask.register("abc");

        assertEquals("abc is a common word", mask.apply("abc is a common word"));
    }

    @Test
    @DisplayName("A throwable chain is described on one masked line")
    void throwableDescription() {
        SecretMask mask = new SecretMask();
        mask.register("github_pat_11ABCDEFG0123456789abcdef");

        Exception cause = new IllegalStateException("token github_pat_11ABCDEFG0123456789abcdef is invalid");
        String described = mask.describe(new RuntimeException("request failed", cause));

        assertTrue(described.contains("RuntimeException: request failed"));
        assertTrue(described.contains("IllegalStateException"));
        assertFalse(described.contains("github_pat_11ABCDEFG0123456789abcdef"));
    }

    @Test
    @DisplayName("Environment references are expanded, and a literal value is left alone")
    void secretExpansion() {
        assertEquals("plain-value", Secrets.expand("plain-value"));
        assertEquals("", Secrets.expand("${NEXOSYNC_DEFINITELY_NOT_SET}"));
        assertEquals("fallback", Secrets.expand("${NEXOSYNC_DEFINITELY_NOT_SET:-fallback}"));
        assertTrue(Secrets.isUnresolved("${NEXOSYNC_DEFINITELY_NOT_SET}", ""));
        assertFalse(Secrets.isUnresolved("plain-value", "plain-value"));
    }

    @Test
    @DisplayName("Only tags following the configured prefix are treated as snapshots")
    void releaseTagParsing() {
        assertEquals(42, GitHubRelease.parseVersion("nexosync-v42", "nexosync-v"));
        assertEquals(1, GitHubRelease.parseVersion("nexosync-v1", "nexosync-v"));

        assertEquals(-1, GitHubRelease.parseVersion("v1.0.0", "nexosync-v"));
        assertEquals(-1, GitHubRelease.parseVersion("nexosync-v42-hotfix", "nexosync-v"));
        assertEquals(-1, GitHubRelease.parseVersion("nexosync-v", "nexosync-v"));
        assertEquals(-1, GitHubRelease.parseVersion("nexosync-v0", "nexosync-v"));
        assertEquals(-1, GitHubRelease.parseVersion(null, "nexosync-v"));
    }

    @Test
    @DisplayName("Backup identifiers survive a round trip through the filesystem")
    void backupIdentifiers() {
        String id = BackupEntry.buildId(41, Instant.parse("2026-09-18T10:30:00Z"));

        Optional<BackupEntry> compressed = BackupEntry.parse(Path.of("backups", id + ".zip"));
        assertTrue(compressed.isPresent());
        assertEquals(41, compressed.get().version());
        assertTrue(compressed.get().compressed());

        Optional<BackupEntry> plain = BackupEntry.parse(Path.of("backups", id));
        assertTrue(plain.isPresent());
        assertFalse(plain.get().compressed());

        assertTrue(BackupEntry.parse(Path.of("backups", "notes.txt")).isEmpty());
        assertTrue(BackupEntry.parse(Path.of("backups", "v41")).isEmpty());
    }

    @Test
    @DisplayName("An unknown deployment mode falls back instead of failing the load")
    void syncModeParsing() {
        assertEquals(SyncMode.PUBLISHER, SyncMode.parse("publisher", SyncMode.BOTH));
        assertEquals(SyncMode.RECEIVER, SyncMode.parse("RECEIVER", SyncMode.BOTH));
        assertEquals(SyncMode.BOTH, SyncMode.parse("nonsense", SyncMode.BOTH));
        assertEquals(SyncMode.BOTH, SyncMode.parse(null, SyncMode.BOTH));

        assertTrue(SyncMode.PUBLISHER.canPush());
        assertFalse(SyncMode.PUBLISHER.canReceive());
        assertTrue(SyncMode.RECEIVER.canReceive());
        assertFalse(SyncMode.RECEIVER.canPush());
        assertTrue(SyncMode.BOTH.canPush() && SyncMode.BOTH.canReceive());
    }
}
