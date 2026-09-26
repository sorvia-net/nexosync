package com.sorvia.nexosync.config;

import java.util.Locale;

/**
 * How NexoSync asks Nexo to reload after a snapshot is installed.
 */
public enum ReloadMode {

    /** Only use Nexo's own API entry point. Fails when that entry point is unavailable. */
    API,

    /** Only dispatch the configured console command. */
    COMMAND,

    /** Try the API first and fall back to the command. */
    AUTO;

    public boolean allowsApi() {
        return this == API || this == AUTO;
    }

    public boolean allowsCommand() {
        return this == COMMAND || this == AUTO;
    }

    public static ReloadMode parse(String raw, ReloadMode fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknownMode) {
            return fallback;
        }
    }
}
