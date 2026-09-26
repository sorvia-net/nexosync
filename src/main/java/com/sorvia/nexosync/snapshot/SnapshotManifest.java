package com.sorvia.nexosync.snapshot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import com.sorvia.nexosync.util.Hashing;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The authoritative description of a snapshot's contents.
 *
 * <p>A manifest travels inside the archive as {@code manifest.json}. Nothing is installed before
 * every file in the staged archive has been matched against it, and the installed copy is kept
 * locally so an interrupted update can be verified after a restart.</p>
 */
public final class SnapshotManifest {

    /** Bumped only when the on-disk format changes in a way older versions cannot read. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public static final String FILE_NAME = "manifest.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    @SerializedName("schema_version")
    public int schemaVersion = CURRENT_SCHEMA_VERSION;

    @SerializedName("snapshot_version")
    public int snapshotVersion;

    @SerializedName("generated_at")
    public String generatedAt;

    public String publisher;

    @SerializedName("nexosync_version")
    public String nexosyncVersion;

    @SerializedName("nexo_version")
    public String nexoVersion;

    @SerializedName("server_version")
    public String serverVersion;

    public String platform;

    @SerializedName("managed_paths")
    public List<String> managedPaths = new ArrayList<>();

    @SerializedName("file_count")
    public int fileCount;

    public List<ManifestEntry> files = new ArrayList<>();

    @SerializedName("snapshot_sha256")
    public String snapshotSha256;

    // -----------------------------------------------------------------------------------------
    // Serialisation
    // -----------------------------------------------------------------------------------------

    public String toJson() {
        return GSON.toJson(this);
    }

    public byte[] toJsonBytes() {
        return toJson().getBytes(StandardCharsets.UTF_8);
    }

    public static SnapshotManifest fromJson(String json) throws JsonSyntaxException {
        SnapshotManifest manifest = GSON.fromJson(json, SnapshotManifest.class);
        if (manifest == null) {
            throw new JsonSyntaxException("Manifest document is empty");
        }
        if (manifest.managedPaths == null) {
            manifest.managedPaths = new ArrayList<>();
        }
        if (manifest.files == null) {
            manifest.files = new ArrayList<>();
        }
        return manifest;
    }

    public static SnapshotManifest fromJson(byte[] json) throws JsonSyntaxException {
        return fromJson(new String(json, StandardCharsets.UTF_8));
    }

    // -----------------------------------------------------------------------------------------
    // Content identity
    // -----------------------------------------------------------------------------------------

    /**
     * Computes the identity of a snapshot from its files alone.
     *
     * <p>Metadata such as the generation timestamp or the publishing server is deliberately
     * excluded: two servers publishing the same content must produce the same value, otherwise the
     * "no changes detected" check would never fire.</p>
     */
    public static String computeSnapshotHash(List<ManifestEntry> entries) {
        List<ManifestEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(entry -> entry.path));

        StringBuilder builder = new StringBuilder();
        for (ManifestEntry entry : sorted) {
            builder.append(entry.path).append('\0')
                    .append(entry.size).append('\0')
                    .append(entry.sha256).append('\n');
        }
        return Hashing.sha256(builder.toString());
    }

    public Map<String, ManifestEntry> byPath() {
        Map<String, ManifestEntry> index = new LinkedHashMap<>();
        for (ManifestEntry entry : files) {
            index.put(entry.path, entry);
        }
        return index;
    }

    public long totalSize() {
        long total = 0L;
        for (ManifestEntry entry : files) {
            total += entry.size;
        }
        return total;
    }

    /**
     * Structural validation that does not touch the filesystem.
     *
     * @return a list of problems; empty when the document is usable
     */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();

        if (schemaVersion <= 0) {
            problems.add("schema_version is missing");
        } else if (schemaVersion > CURRENT_SCHEMA_VERSION) {
            problems.add("schema_version " + schemaVersion + " is newer than this NexoSync build supports");
        }
        if (snapshotVersion <= 0) {
            problems.add("snapshot_version is missing or not positive");
        }
        if (managedPaths.isEmpty()) {
            problems.add("managed_paths is empty");
        }
        if (snapshotSha256 == null || snapshotSha256.length() != 64) {
            problems.add("snapshot_sha256 is missing or malformed");
        }
        if (fileCount != files.size()) {
            problems.add("file_count (" + fileCount + ") does not match the number of listed files (" + files.size() + ")");
        }
        for (ManifestEntry entry : files) {
            if (!entry.isComplete()) {
                problems.add("file entry is incomplete: " + entry);
                break;
            }
        }
        if (problems.isEmpty()) {
            String recomputed = computeSnapshotHash(files);
            if (!recomputed.equals(snapshotSha256)) {
                problems.add("snapshot_sha256 does not match the listed files");
            }
        }
        return problems;
    }
}
