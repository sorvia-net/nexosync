package com.sorvia.nexosync.config;

import java.util.Locale;

/**
 * Deployment role of this server.
 *
 * <p>The role is a local safety switch, not a network-wide election: NexoSync has no master server.
 * Any installation holding a token with write access can publish, and the mode simply decides which
 * behaviour this particular installation is allowed to perform.</p>
 */
public enum SyncMode {

    /** May publish snapshots, never installs one automatically. */
    PUBLISHER,

    /** May check for and install snapshots, never publishes. */
    RECEIVER,

    /** May do both. */
    BOTH;

    public boolean canPush() {
        return this == PUBLISHER || this == BOTH;
    }

    public boolean canReceive() {
        return this == RECEIVER || this == BOTH;
    }

    public static SyncMode parse(String raw, SyncMode fallback) {
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
