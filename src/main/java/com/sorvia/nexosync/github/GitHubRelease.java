package com.sorvia.nexosync.github;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A GitHub release, reduced to what NexoSync needs.
 *
 * <p>{@link #snapshotVersion()} is parsed from the tag using the configured prefix. A release whose
 * tag does not follow the NexoSync pattern reports {@code -1} and is ignored, so unrelated releases
 * can live in the same repository without confusing the update check.</p>
 */
public record GitHubRelease(
        long id,
        String tagName,
        String name,
        String body,
        boolean draft,
        boolean prerelease,
        String publishedAt,
        String uploadUrl,
        String htmlUrl,
        int snapshotVersion,
        List<GitHubAsset> assets) {

    public static GitHubRelease from(JsonObject json, String tagPrefix) {
        List<GitHubAsset> assets = new ArrayList<>();
        if (json.has("assets") && json.get("assets").isJsonArray()) {
            JsonArray array = json.getAsJsonArray("assets");
            for (JsonElement element : array) {
                if (element.isJsonObject()) {
                    assets.add(GitHubAsset.from(element.getAsJsonObject()));
                }
            }
        }

        String tag = optionalString(json, "tag_name");
        return new GitHubRelease(
                json.has("id") ? json.get("id").getAsLong() : -1L,
                tag,
                optionalString(json, "name"),
                optionalString(json, "body"),
                json.has("draft") && json.get("draft").getAsBoolean(),
                json.has("prerelease") && json.get("prerelease").getAsBoolean(),
                optionalString(json, "published_at"),
                optionalString(json, "upload_url"),
                optionalString(json, "html_url"),
                parseVersion(tag, tagPrefix),
                List.copyOf(assets));
    }

    /**
     * Extracts the numeric snapshot version from a tag such as {@code nexosync-v42}.
     *
     * @return the version, or -1 when the tag does not belong to NexoSync
     */
    public static int parseVersion(String tag, String tagPrefix) {
        if (tag == null || tagPrefix == null || !tag.startsWith(tagPrefix)) {
            return -1;
        }
        String remainder = tag.substring(tagPrefix.length());
        if (remainder.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < remainder.length(); i++) {
            if (!Character.isDigit(remainder.charAt(i))) {
                return -1;
            }
        }
        try {
            int parsed = Integer.parseInt(remainder);
            return parsed > 0 ? parsed : -1;
        } catch (NumberFormatException tooLarge) {
            return -1;
        }
    }

    public boolean isNexoSyncRelease() {
        return snapshotVersion > 0;
    }

    /**
     * Finds the snapshot asset. The configured name is preferred; any single {@code .zip} asset is
     * accepted as a fallback so a manually renamed upload still works.
     */
    public Optional<GitHubAsset> findSnapshotAsset(String expectedName) {
        for (GitHubAsset asset : assets) {
            if (asset.name().equalsIgnoreCase(expectedName)) {
                return Optional.of(asset);
            }
        }
        List<GitHubAsset> zips = assets.stream()
                .filter(asset -> asset.name().toLowerCase(java.util.Locale.ROOT).endsWith(".zip"))
                .toList();
        return zips.size() == 1 ? Optional.of(zips.get(0)) : Optional.empty();
    }

    /**
     * Turns the templated upload URL GitHub returns into a usable address.
     */
    public String resolveUploadUrl(String assetName) {
        String base = uploadUrl;
        int template = base.indexOf('{');
        if (template >= 0) {
            base = base.substring(0, template);
        }
        return base + "?name=" + java.net.URLEncoder.encode(assetName, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String optionalString(JsonObject json, String member) {
        return json.has(member) && !json.get(member).isJsonNull() ? json.get(member).getAsString() : "";
    }
}
