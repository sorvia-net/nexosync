package com.sorvia.nexosync.log;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the startup banner's layout and its two degradation paths.
 *
 * <p>A banner is cosmetic, but it is the first thing anyone sees in the console, and the ways it
 * breaks are silent on the developer's machine: a UTF-8 terminal never shows the mojibake a Windows
 * code page produces, and a terminal that renders ANSI never shows the escape sequences a plain one
 * prints literally. Both are asserted here instead of being discovered on someone's server.</p>
 */
class StartupBannerTest {

    private static final String ESCAPE = "\u001B";

    @Test
    @DisplayName("The caption reports the name and version it was given, not a hardcoded pair")
    void captionUsesSuppliedMetadata() {
        List<String> lines = StartupBanner.lines("NexoSync", "4.7.1-SNAPSHOT", false, true, true);

        String caption = lines.get(lines.size() - 2);
        assertTrue(caption.contains("NexoSync"), caption);
        assertTrue(caption.contains("4.7.1-SNAPSHOT"), caption);
    }

    @Test
    @DisplayName("Every logo row is padded to the same width, so the trailing hearts line up")
    void logoRowsShareOneWidth() {
        for (boolean blockLogo : new boolean[] {true, false}) {
            List<String> lines = StartupBanner.lines("NexoSync", "1.0.0", false, blockLogo, true);
            List<String> logoRows = lines.subList(1, lines.size() - 3);

            int expected = logoRows.get(0).length();
            for (String row : logoRows) {
                assertEquals(expected, row.length(), "row misaligned with blockLogo=" + blockLogo + ": " + row);
            }
        }
    }

    @Test
    @DisplayName("The fallback logo and heart are plain ASCII, for a console that cannot encode more")
    void fallbackRendersAsAscii() {
        List<String> lines = StartupBanner.lines("NexoSync", "1.0.0", false, false, false);

        String joined = String.join("\n", lines);
        assertTrue(StandardCharsets.US_ASCII.newEncoder().canEncode(joined),
                "fallback banner contains characters a US-ASCII console cannot encode");
        assertTrue(joined.contains("<3"), "expected the ASCII heart");
    }

    @Test
    @DisplayName("Colour is confined to the colour mode; plain mode emits no escape sequences")
    void colourIsOptional() {
        String plain = String.join("\n", StartupBanner.lines("NexoSync", "1.0.0", false, true, true));
        String coloured = String.join("\n", StartupBanner.lines("NexoSync", "1.0.0", true, true, true));

        assertFalse(plain.contains(ESCAPE), "plain banner leaked an ANSI escape sequence");
        assertTrue(coloured.contains(ESCAPE), "coloured banner emitted no ANSI escape sequence");
    }

    @Test
    @DisplayName("The box-drawing logo is offered only to a charset that can encode it")
    void blockLogoIsGatedOnTheConsoleCharset() {
        assertTrue(StartupBanner.blockLogoSupported(StandardCharsets.UTF_8));
        assertFalse(StartupBanner.blockLogoSupported(StandardCharsets.US_ASCII));
        assertFalse(StartupBanner.blockLogoSupported(StandardCharsets.ISO_8859_1));
    }

    @Test
    @DisplayName("An unusable stdout.encoding falls back instead of failing the server start")
    void unknownConsoleEncodingFallsBack() {
        String previous = System.getProperty("stdout.encoding");
        System.setProperty("stdout.encoding", "not-a-real-charset");
        try {
            Charset resolved = StartupBanner.consoleCharset();
            assertEquals(Charset.defaultCharset(), resolved);
        } finally {
            if (previous == null) {
                System.clearProperty("stdout.encoding");
            } else {
                System.setProperty("stdout.encoding", previous);
            }
        }
    }
}
