package com.sorvia.nexosync.notify;

/**
 * The webhook events an operator can switch on or off in {@code discord.events}.
 *
 * <p>The default reflects what is worth waking someone up for: outcomes are reported, routine
 * progress is not.</p>
 */
public enum DiscordEvent {

    PUSH_START("push-start", false),
    PUSH_SUCCESS("push-success", true),
    PUSH_FAILURE("push-failure", true),

    UPDATE_AVAILABLE("update-available", true),
    UPDATE_START("update-start", false),
    UPDATE_SUCCESS("update-success", true),
    UPDATE_FAILURE("update-failure", true),

    ROLLBACK_START("rollback-start", false),
    ROLLBACK_SUCCESS("rollback-success", true),
    ROLLBACK_FAILURE("rollback-failure", true),

    CHECK_FAILURE("check-failure", true),
    INTEGRITY_FAILURE("integrity-failure", true),
    GITHUB_FAILURE("github-failure", true),

    NO_UPDATE("no-update", false),
    STARTUP("startup", false);

    private final String key;
    private final boolean enabledByDefault;

    DiscordEvent(String key, boolean enabledByDefault) {
        this.key = key;
        this.enabledByDefault = enabledByDefault;
    }

    public String key() {
        return key;
    }

    public boolean enabledByDefault() {
        return enabledByDefault;
    }
}
