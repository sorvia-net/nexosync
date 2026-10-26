package com.sorvia.nexosync.log;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Prints the Sorvia banner once, at the very start of {@code onEnable}.
 *
 * <p>This deliberately bypasses {@link NexoSyncLogger} and the plugin logger. Anything written
 * through those is routed into Log4j, which stamps every line with {@code [HH:mm:ss INFO]:} - and a
 * six-line logo whose rows each carry a different timestamp is no longer aligned. Writing straight
 * to the process's standard output is the only way to keep the logo intact.</p>
 *
 * <p>Two things about a Minecraft console make a naive implementation render as garbage, and both
 * are handled here rather than assumed away:</p>
 *
 * <ul>
 *   <li><b>The console is often not UTF-8.</b> A Windows console typically runs on a legacy code
 *       page, and writing the box-drawing logo through a stream that assumes UTF-8 produces
 *       mojibake. The stream is therefore opened with the charset the console actually reports,
 *       and the logo degrades to a pure-ASCII variant when those characters cannot be encoded at
 *       all.</li>
 *   <li><b>ANSI colour is not universal.</b> Honoured when available, dropped entirely when
 *       {@code NO_COLOR} is set, when {@code TERM} is {@code dumb}, or when
 *       {@code -Dnexosync.banner.plain=true} is passed.</li>
 * </ul>
 *
 * <p>The plugin name and version are passed in from the plugin's own metadata, never hardcoded, so
 * the caption cannot drift away from the version in {@code plugin.yml}.</p>
 */
public final class StartupBanner {

    private static final String ORANGE = "\u001B[1;38;5;208m";
    private static final String PINK = "\u001B[38;5;205m";
    private static final String WHITE = "\u001B[1;37m";
    private static final String RESET = "\u001B[0m";

    private static final String[] BLOCK_LOGO = {
        " ███████╗  ██████╗  ██████╗  ██╗   ██╗ ██╗  █████╗ ",
        " ██╔════╝ ██╔═══██╗ ██╔══██╗ ██║   ██║ ██║ ██╔══██╗",
        " ███████╗ ██║   ██║ ██████╔╝ ██║   ██║ ██║ ███████║",
        " ╚════██║ ██║   ██║ ██╔══██╗ ╚██╗ ██╔╝ ██║ ██╔══██║",
        " ███████║ ╚██████╔╝ ██║  ██║  ╚████╔╝  ██║ ██║  ██║",
        " ╚══════╝  ╚═════╝  ╚═╝  ╚═╝   ╚═══╝   ╚═╝ ╚═╝  ╚═╝"
    };

    private static final String[] ASCII_LOGO = {
        "  ____    ___   ____   __     __ ___     _    ",
        " / ___|  / _ \\ |  _ \\  \\ \\   / /|_ _|   / \\   ",
        " \\___ \\ | | | || |_) |  \\ \\ / /  | |   / _ \\  ",
        "  ___) || |_| ||  _ <    \\ V /   | |  / ___ \\ ",
        " |____/  \\___/ |_| \\_\\    \\_/   |___|/_/   \\_\\"
    };

    private StartupBanner() {
    }

    /**
     * Builds the banner without printing it, so the layout can be asserted in a test.
     *
     * @param pluginName   the plugin's name, taken from its metadata
     * @param version      the plugin's version, taken from its metadata
     * @param color        whether to wrap the output in ANSI escape sequences
     * @param blockLogo    whether the console can render the box-drawing logo
     * @param unicodeHeart whether the console can render {@code ♥}; {@code <3} is used when it cannot
     */
    public static List<String> lines(String pluginName, String version, boolean color, boolean blockLogo,
                                     boolean unicodeHeart) {
        String[] logo = blockLogo ? BLOCK_LOGO : ASCII_LOGO;
        int width = 0;
        for (String line : logo) {
            width = Math.max(width, line.length());
        }

        String heartGlyph = unicodeHeart ? "♥" : "<3";
        String heart = color ? PINK + heartGlyph + RESET : heartGlyph;

        List<String> out = new ArrayList<>();
        out.add("");
        for (String line : logo) {
            // Padding happens before the colour codes are added; escape sequences occupy no columns
            // on screen but do count towards String.length, so padding a coloured string misaligns it.
            String padded = String.format("%-" + width + "s", line);
            out.add(heart + "  " + (color ? ORANGE + padded + RESET : padded) + "  " + heart);
        }
        out.add("");

        String caption = pluginName + " | Version " + version;
        int total = width + 4 + heartGlyph.length() * 2;
        String indent = " ".repeat(Math.max(0, (total - caption.length()) / 2));
        out.add(indent + (color ? WHITE + caption + RESET : caption));
        out.add("");
        return out;
    }

    /**
     * Reports whether ANSI escape sequences should be emitted.
     *
     * <p>{@code NO_COLOR} is the cross-tool convention for "never colour your output"; the system
     * property is the per-server override for a console that claims colour support it does not have.</p>
     */
    public static boolean colorSupported() {
        if (System.getenv("NO_COLOR") != null || Boolean.getBoolean("nexosync.banner.plain")) {
            return false;
        }
        String term = System.getenv("TERM");
        return term == null || !term.equalsIgnoreCase("dumb");
    }

    /**
     * Resolves the charset the console actually decodes bytes with, which on Windows is a legacy
     * code page rather than the JVM's file encoding.
     */
    static Charset consoleCharset() {
        String name = System.getProperty("stdout.encoding");
        try {
            if (name != null) {
                return Charset.forName(name);
            }
        } catch (RuntimeException unsupportedName) {
            // An unknown or malformed encoding name is not worth failing a server start over;
            // the platform default is always usable, at worst showing the ASCII logo instead.
        }
        return Charset.defaultCharset();
    }

    static boolean blockLogoSupported(Charset charset) {
        CharsetEncoder encoder = charset.newEncoder();
        for (String line : BLOCK_LOGO) {
            if (!encoder.canEncode(line)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Writes the banner to standard output.
     *
     * @param pluginName the plugin's name, taken from its metadata
     * @param version    the plugin's version, taken from its metadata
     */
    @SuppressWarnings("resource") // see below: closing this stream would close the server console
    public static void print(String pluginName, String version) {
        Charset charset = consoleCharset();
        // Intentionally never closed: this stream wraps the process's own stdout, and closing it
        // would take the server console with it. It is created once and left to the JVM.
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, charset);
        for (String line : lines(pluginName, version, colorSupported(), blockLogoSupported(charset),
                charset.newEncoder().canEncode("♥"))) {
            out.println(line);
        }
        out.flush();
    }
}
