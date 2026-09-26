package com.sorvia.nexosync.github;

import com.google.gson.JsonObject;

/**
 * A release asset as returned by the GitHub REST API.
 *
 * <p>{@link #apiUrl()} is the address used for downloading: unlike the browser URL it works for
 * private repositories when an authorisation header is present.</p>
 */
public record GitHubAsset(long id, String name, long size, String contentType, String apiUrl, String browserUrl) {

    public static GitHubAsset from(JsonObject json) {
        return new GitHubAsset(
                json.has("id") ? json.get("id").getAsLong() : -1L,
                optionalString(json, "name"),
                json.has("size") ? json.get("size").getAsLong() : -1L,
                optionalString(json, "content_type"),
                optionalString(json, "url"),
                optionalString(json, "browser_download_url"));
    }

    private static String optionalString(JsonObject json, String member) {
        return json.has(member) && !json.get(member).isJsonNull() ? json.get(member).getAsString() : "";
    }
}
