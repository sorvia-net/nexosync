package com.sorvia.nexosync.notify;

import java.util.ArrayList;
import java.util.List;

/**
 * A webhook embed under construction.
 *
 * <p>Callers describe what happened; the notifier decides what is actually allowed to be sent based
 * on the {@code discord.include} switches.</p>
 */
public final class DiscordReport {

    /** Green - an operation finished as intended. */
    public static final int COLOR_SUCCESS = 0x2ECC71;
    /** Red - an operation failed. */
    public static final int COLOR_FAILURE = 0xE74C3C;
    /** Amber - something needs attention but nothing broke. */
    public static final int COLOR_WARNING = 0xF1C40F;
    /** Blue - informational. */
    public static final int COLOR_INFO = 0x3498DB;
    /** Deep red - reserved for a failed rollback, the one state that needs a human now. */
    public static final int COLOR_CRITICAL = 0x992D22;

    private final String title;
    private final int color;
    private final List<Field> fields = new ArrayList<>();

    private String description;

    public DiscordReport(String title, int color) {
        this.title = title;
        this.color = color;
    }

    public record Field(String name, String value, boolean inline) {
    }

    public DiscordReport description(String description) {
        this.description = description;
        return this;
    }

    public DiscordReport field(String name, String value) {
        return field(name, value, true);
    }

    public DiscordReport field(String name, String value, boolean inline) {
        if (name != null && !name.isBlank() && value != null && !value.isBlank()) {
            fields.add(new Field(name, value, inline));
        }
        return this;
    }

    /**
     * Adds a field only when the corresponding {@code discord.include} switch is on.
     */
    public DiscordReport fieldIf(boolean condition, String name, String value) {
        return condition ? field(name, value, true) : this;
    }

    public DiscordReport blockIf(boolean condition, String name, String value) {
        return condition ? field(name, value, false) : this;
    }

    public String title() {
        return title;
    }

    public int color() {
        return color;
    }

    public String description() {
        return description;
    }

    public List<Field> fields() {
        return List.copyOf(fields);
    }
}
