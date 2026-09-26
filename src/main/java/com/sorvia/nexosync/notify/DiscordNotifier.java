package com.sorvia.nexosync.notify;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sorvia.nexosync.config.NexoSyncConfig;
import com.sorvia.nexosync.log.NexoSyncLogger;
import com.sorvia.nexosync.platform.PlatformScheduler;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * Sends operation reports to a Discord webhook.
 *
 * <p>Notifications are strictly best effort: they run off the operation thread and a webhook failure
 * is logged but never turns a successful update into a failed one. Every field passes through the
 * secret mask before it is serialised, so a token echoed inside an error message cannot leave the
 * server through this path.</p>
 */
public final class DiscordNotifier {

    private static final int MAX_FIELD_LENGTH = 1000;
    private static final int MAX_DESCRIPTION_LENGTH = 3800;

    private final NexoSyncConfig config;
    private final NexoSyncLogger log;
    private final PlatformScheduler scheduler;
    private final HttpClient http;
    private final String serverName;

    public DiscordNotifier(NexoSyncConfig config, NexoSyncLogger log, PlatformScheduler scheduler, String serverName) {
        this.config = config;
        this.log = log;
        this.scheduler = scheduler;
        this.serverName = serverName;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public boolean isActive() {
        return config.discord().isActive();
    }

    public NexoSyncConfig.DiscordSection.IncludeSection include() {
        return config.discord().include();
    }

    public String serverName() {
        return serverName;
    }

    /**
     * Queues a report. Returns immediately; delivery happens on a worker thread.
     */
    public void send(String operationId, DiscordEvent event, DiscordReport report) {
        if (!isActive()) {
            return;
        }
        if (!config.discord().isEventEnabled(event.key(), event.enabledByDefault())) {
            return;
        }

        String payload = buildPayload(report);
        scheduler.async(() -> deliver(operationId, payload));
    }

    private String buildPayload(DiscordReport report) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", log.mask().apply(report.title()));
        embed.addProperty("color", report.color());
        embed.addProperty("timestamp", Instant.now().toString());

        if (report.description() != null && !report.description().isBlank()) {
            embed.addProperty("description", truncate(log.mask().apply(report.description()), MAX_DESCRIPTION_LENGTH));
        }

        JsonArray fields = new JsonArray();
        for (DiscordReport.Field field : report.fields()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", log.mask().apply(field.name()));
            entry.addProperty("value", truncate(log.mask().apply(field.value()), MAX_FIELD_LENGTH));
            entry.addProperty("inline", field.inline());
            fields.add(entry);
        }
        if (!fields.isEmpty()) {
            embed.add("fields", fields);
        }

        JsonObject footer = new JsonObject();
        footer.addProperty("text", "NexoSync - Sorvia Development Solutions");
        embed.add("footer", footer);

        JsonArray embeds = new JsonArray();
        embeds.add(embed);

        JsonObject payload = new JsonObject();
        payload.add("embeds", embeds);
        if (config.discord().username() != null && !config.discord().username().isBlank()) {
            payload.addProperty("username", config.discord().username());
        }
        if (config.discord().avatarUrl() != null && !config.discord().avatarUrl().isBlank()) {
            payload.addProperty("avatar_url", config.discord().avatarUrl());
        }
        return payload.toString();
    }

    private void deliver(String operationId, String payload) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(config.discord().webhookUrl()))
                    .setHeader("Content-Type", "application/json")
                    .setHeader("User-Agent", "NexoSync (Sorvia Development Solutions)")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();

            if (status == 429) {
                log.warn(operationId, "Discord rate limited the webhook; this notification was dropped.");
            } else if (status < 200 || status >= 300) {
                log.warn(operationId, "Discord webhook returned HTTP " + status + ".");
            } else {
                log.debug(operationId, "Discord notification delivered.");
            }
        } catch (IOException deliveryFailed) {
            log.warn(operationId, "Discord webhook delivery failed: " + deliveryFailed.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException malformed) {
            log.warn(operationId, "Discord webhook could not be used: " + malformed.getMessage());
        }
    }

    private static String truncate(String value, int limit) {
        if (value == null) {
            return "";
        }
        return value.length() <= limit ? value : value.substring(0, limit - 3) + "...";
    }
}
