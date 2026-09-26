package com.sorvia.nexosync;

import com.sorvia.nexosync.snapshot.ManifestEntry;
import com.sorvia.nexosync.snapshot.SnapshotDiff;
import com.sorvia.nexosync.snapshot.SnapshotManifest;
import com.sorvia.nexosync.util.Hashing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the manifest rules the whole synchronization model rests on.
 *
 * <p>Two properties matter most. A snapshot's identity must depend on content alone, otherwise two
 * servers publishing the same files would produce different hashes and the "no changes" check would
 * never fire. And a manifest whose file list has been altered must fail validation, because that is
 * the check standing between a tampered release and the live Nexo directory.</p>
 */
class SnapshotIntegrityTest {

    @Test
    @DisplayName("A well-formed manifest reports no problems and survives a JSON round trip")
    void manifestRoundTrip() {
        SnapshotManifest manifest = manifest(42,
                entry("items/ruby.yml", 10, "ruby"),
                entry("glyphs/menu.yml", 20, "menu"));

        assertTrue(manifest.problems().isEmpty(), () -> String.join("; ", manifest.problems()));

        String json = manifest.toJson();
        assertTrue(json.contains("\"snapshot_version\""), "the on-disk format uses snake_case keys");

        SnapshotManifest parsed = SnapshotManifest.fromJson(json);
        assertEquals(42, parsed.snapshotVersion);
        assertEquals(2, parsed.files.size());
        assertEquals(manifest.snapshotSha256, parsed.snapshotSha256);
        assertTrue(parsed.problems().isEmpty());
    }

    @Test
    @DisplayName("Snapshot identity depends on content, not on collection order")
    void snapshotHashIsOrderIndependent() {
        ManifestEntry first = entry("items/ruby.yml", 10, "ruby");
        ManifestEntry second = entry("glyphs/menu.yml", 20, "menu");

        assertEquals(
                SnapshotManifest.computeSnapshotHash(List.of(first, second)),
                SnapshotManifest.computeSnapshotHash(List.of(second, first)));
    }

    @Test
    @DisplayName("Changing a single file changes the snapshot hash")
    void snapshotHashReactsToContent() {
        String before = SnapshotManifest.computeSnapshotHash(List.of(entry("items/ruby.yml", 10, "ruby")));
        String after = SnapshotManifest.computeSnapshotHash(List.of(entry("items/ruby.yml", 10, "emerald")));

        assertNotEquals(before, after);
    }

    @Test
    @DisplayName("A manifest whose file list was altered fails validation")
    void tamperedManifestIsRejected() {
        SnapshotManifest manifest = manifest(42, entry("items/ruby.yml", 10, "ruby"));
        assertTrue(manifest.problems().isEmpty());

        manifest.files.get(0).sha256 = Hashing.sha256("something else");
        assertFalse(manifest.problems().isEmpty(), "an edited file list must not validate");
    }

    @Test
    @DisplayName("An incomplete manifest is rejected before anything is installed")
    void incompleteManifestIsRejected() {
        SnapshotManifest manifest = manifest(42, entry("items/ruby.yml", 10, "ruby"));

        manifest.fileCount = 99;
        assertFalse(manifest.problems().isEmpty(), "file_count must agree with the listed files");

        SnapshotManifest unversioned = manifest(0, entry("items/ruby.yml", 10, "ruby"));
        assertFalse(unversioned.problems().isEmpty(), "a snapshot must carry a positive version");
    }

    @Test
    @DisplayName("A schema newer than this build understands is rejected")
    void futureSchemaIsRejected() {
        SnapshotManifest manifest = manifest(42, entry("items/ruby.yml", 10, "ruby"));
        manifest.schemaVersion = SnapshotManifest.CURRENT_SCHEMA_VERSION + 1;

        assertFalse(manifest.problems().isEmpty());
    }

    @Test
    @DisplayName("The diff reports added, modified and removed files separately")
    void diffClassifiesChanges() {
        SnapshotManifest before = manifest(1,
                entry("items/keep.yml", 1, "keep"),
                entry("items/change.yml", 1, "old"),
                entry("items/gone.yml", 1, "gone"));
        SnapshotManifest after = manifest(2,
                entry("items/keep.yml", 1, "keep"),
                entry("items/change.yml", 1, "new"),
                entry("items/added.yml", 1, "added"));

        SnapshotDiff diff = SnapshotDiff.between(before, after);

        assertEquals(List.of("items/added.yml"), diff.added());
        assertEquals(List.of("items/change.yml"), diff.modified());
        assertEquals(List.of("items/gone.yml"), diff.removed());
        assertEquals(3, diff.totalChanges());
    }

    @Test
    @DisplayName("The first install reports every file as added")
    void diffAgainstNothing() {
        SnapshotManifest after = manifest(1, entry("items/a.yml", 1, "a"), entry("items/b.yml", 1, "b"));

        SnapshotDiff diff = SnapshotDiff.between(null, after);

        assertEquals(2, diff.added().size());
        assertTrue(diff.modified().isEmpty());
        assertTrue(diff.removed().isEmpty());
    }

    // -------------------------------------------------------------------------------------------

    private static ManifestEntry entry(String path, long size, String content) {
        return new ManifestEntry(path, size, Hashing.sha256(content));
    }

    private static SnapshotManifest manifest(int version, ManifestEntry... entries) {
        SnapshotManifest manifest = new SnapshotManifest();
        manifest.snapshotVersion = version;
        manifest.generatedAt = "2026-09-18T12:00:00Z";
        manifest.publisher = "Lobby";
        manifest.nexosyncVersion = "1.0.0";
        manifest.nexoVersion = "test";
        manifest.serverVersion = "1.21.4";
        manifest.platform = "Paper";
        manifest.managedPaths = List.of("glyphs", "items");
        manifest.files = new ArrayList<>(List.of(entries));
        manifest.fileCount = manifest.files.size();
        manifest.snapshotSha256 = SnapshotManifest.computeSnapshotHash(manifest.files);
        return manifest;
    }
}
