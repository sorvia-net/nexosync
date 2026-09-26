package com.sorvia.nexosync.util;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes credentials from anything that may reach a log file, a chat message or a webhook.
 *
 * <p>Two layers are applied: exact values registered from the resolved configuration, and generic
 * patterns that catch credentials this instance never saw - for example a token echoed back inside
 * a third-party stack trace.</p>
 */
public final class SecretMask {

    private static final String REDACTED = "[REDACTED]";

    private static final List<Pattern> GENERIC_PATTERNS = List.of(
            Pattern.compile("gh[pousr]_[A-Za-z0-9]{16,}"),
            Pattern.compile("github_pat_[A-Za-z0-9_]{20,}"),
            Pattern.compile("(?i)https://(?:ptb\\.|canary\\.)?discord(?:app)?\\.com/api/webhooks/\\S+"),
            Pattern.compile("(?i)(authorization\\s*[:=]\\s*)(?:bearer|token|basic)\\s+\\S+"),
            Pattern.compile("(?i)(x-hub-signature[^\\s:=]*\\s*[:=]\\s*)\\S+"));

    private final List<String> literals = new CopyOnWriteArrayList<>();

    /**
     * Registers a literal secret value.
     *
     * <p>Short or blank values are ignored, so masking never degenerates into replacing common
     * substrings that happen to appear in ordinary messages.</p>
     */
    public void register(String secret) {
        if (secret == null) {
            return;
        }
        String trimmed = secret.trim();
        if (trimmed.length() >= 8 && !literals.contains(trimmed)) {
            literals.add(trimmed);
        }
    }

    public void clear() {
        literals.clear();
    }

    public String apply(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String result = input;

        for (String literal : literals) {
            if (result.contains(literal)) {
                result = result.replace(literal, REDACTED);
            }
        }
        for (Pattern pattern : GENERIC_PATTERNS) {
            result = maskPattern(pattern, result);
        }
        return result;
    }

    /**
     * Replaces every match, keeping the first capturing group when the pattern defines one. That
     * preserves a readable prefix such as {@code Authorization: } while removing the value.
     */
    private static String maskPattern(Pattern pattern, String input) {
        Matcher matcher = pattern.matcher(input);
        if (!matcher.find()) {
            return input;
        }
        matcher.reset();

        StringBuilder builder = new StringBuilder();
        while (matcher.find()) {
            String prefix = matcher.groupCount() >= 1 && matcher.group(1) != null ? matcher.group(1) : "";
            matcher.appendReplacement(builder, Matcher.quoteReplacement(prefix + REDACTED));
        }
        matcher.appendTail(builder);
        return builder.toString();
    }

    /**
     * Produces a masked, single-line description of a throwable chain.
     */
    public String describe(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        Throwable current = throwable;
        int depth = 0;

        while (current != null && depth < 5) {
            if (depth > 0) {
                builder.append(" <- ");
            }
            builder.append(current.getClass().getSimpleName());
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                builder.append(": ").append(current.getMessage());
            }
            current = current.getCause();
            depth++;
        }
        return apply(builder.toString());
    }
}
