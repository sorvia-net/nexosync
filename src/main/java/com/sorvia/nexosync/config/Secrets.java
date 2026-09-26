package com.sorvia.nexosync.config;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands environment-variable references inside configuration values.
 *
 * <p>This is a NexoSync configuration feature and has nothing to do with GitHub: the value
 * {@code ${NEXOSYNC_GITHUB_TOKEN}} is resolved locally so credentials never have to be written into
 * {@code config.yml}. A JVM system property with the same name is accepted as a secondary source,
 * which is convenient for panels that only allow extra JVM flags.</p>
 *
 * <p>Supported forms:</p>
 * <pre>
 * ${NAME}              resolved from the environment, then system properties
 * ${NAME:-fallback}    same, with a literal fallback when unset or empty
 * </pre>
 */
public final class Secrets {

    private static final Pattern REFERENCE =
            Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_.]*)(?::-([^}]*))?}");

    private Secrets() {
    }

    public static String expand(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw == null ? "" : raw;
        }
        Matcher matcher = REFERENCE.matcher(raw);
        if (!matcher.find()) {
            return raw;
        }
        matcher.reset();

        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String fallback = matcher.group(2) == null ? "" : matcher.group(2);

            String value = System.getenv(name);
            if (value == null || value.isEmpty()) {
                value = System.getProperty(name, "");
            }
            if (value.isEmpty()) {
                value = fallback;
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Returns true when the raw value still contains an unresolved reference, which means the
     * environment variable was never set on this machine.
     */
    public static boolean isUnresolved(String raw, String expanded) {
        return REFERENCE.matcher(raw == null ? "" : raw).find() && (expanded == null || expanded.isBlank());
    }
}
