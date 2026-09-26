package com.sorvia.nexosync.util;

import java.util.UUID;

/**
 * Short correlation identifiers.
 *
 * <p>One identifier is generated per operation and printed by every stage, so an administrator can
 * follow a single update across console output, the log file and Discord.</p>
 */
public final class OperationId {

    private OperationId() {
    }

    public static String next() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
