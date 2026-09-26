package com.sorvia.nexosync;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the bundled translations in step with each other.
 *
 * <p>A translation that silently loses a key is not a visible failure at runtime - the message just
 * falls back to English - so the mismatch would go unnoticed until someone reads their own language
 * and finds half the plugin speaking another one. This test makes that a build failure instead.</p>
 */
class LanguageFileTest {

    private static final List<String> BUNDLED = List.of("en_US", "tr_TR");
    private static final String REFERENCE = "en_US";

    /** Matches the {placeholder} tokens the code substitutes at render time. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z-]+)}");

    @Test
    @DisplayName("Every bundled language ships inside the JAR resources")
    void bundledFilesExist() throws IOException {
        for (String language : BUNDLED) {
            try (InputStream in = open(language)) {
                assertNotNull(in, "lang/" + language + ".yml is missing from src/main/resources");
            }
        }
    }

    @Test
    @DisplayName("Every translation defines exactly the keys the English file defines")
    void translationsCoverAllKeys() throws IOException {
        Set<String> reference = keysOf(REFERENCE);
        assertTrue(reference.size() > 50, "the reference file should define the full message set");

        for (String language : BUNDLED) {
            if (language.equals(REFERENCE)) {
                continue;
            }
            Set<String> translated = keysOf(language);

            Set<String> missing = new TreeSet<>(reference);
            missing.removeAll(translated);
            assertTrue(missing.isEmpty(), () -> language + " is missing: " + missing);

            Set<String> extra = new TreeSet<>(translated);
            extra.removeAll(reference);
            assertTrue(extra.isEmpty(), () -> language + " defines unknown keys: " + extra);
        }
    }

    @Test
    @DisplayName("Every translation keeps the placeholders its message needs")
    void translationsKeepPlaceholders() throws IOException {
        YamlConfiguration reference = load(REFERENCE);

        for (String language : BUNDLED) {
            if (language.equals(REFERENCE)) {
                continue;
            }
            YamlConfiguration translated = load(language);

            for (String key : keysOf(REFERENCE)) {
                Set<String> expected = placeholdersIn(reference.getString(key));
                Set<String> actual = placeholdersIn(translated.getString(key));

                assertEquals(expected, actual,
                        () -> language + " changed the placeholders of '" + key + "'");
            }
        }
    }

    @Test
    @DisplayName("The message prefix is present in every language")
    void prefixIsDefined() throws IOException {
        for (String language : BUNDLED) {
            String prefix = load(language).getString("prefix");
            assertNotNull(prefix, language + " does not define a prefix");
            assertTrue(prefix.contains("NexoSync"), language + " prefix should name the plugin");
        }
    }

    // -------------------------------------------------------------------------------------------

    private static InputStream open(String language) {
        return LanguageFileTest.class.getClassLoader().getResourceAsStream("lang/" + language + ".yml");
    }

    private static YamlConfiguration load(String language) throws IOException {
        try (InputStream in = open(language)) {
            assertNotNull(in, "lang/" + language + ".yml is missing");
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    /**
     * Returns the leaf keys only; a parent section carries no message of its own.
     */
    private static Set<String> keysOf(String language) throws IOException {
        YamlConfiguration configuration = load(language);
        Set<String> leaves = new TreeSet<>();

        for (String key : configuration.getKeys(true)) {
            if (!configuration.isConfigurationSection(key)) {
                leaves.add(key);
            }
        }
        return leaves;
    }

    private static Set<String> placeholdersIn(String message) {
        Set<String> found = new TreeSet<>();
        if (message == null) {
            return found;
        }
        Matcher matcher = PLACEHOLDER.matcher(message);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    /**
     * Messages that are legitimately identical in every language.
     *
     * <p>These are labels made entirely of proper nouns or of words that are spelled the same in
     * both languages. Keeping the list explicit means a genuinely untranslated sentence still fails
     * the build instead of hiding among them.</p>
     */
    private static final Set<String> INTENTIONALLY_IDENTICAL = Set.of(
            "prefix",               // branding
            "status.nexo",          // "Nexo:" - product name
            "status.platform",      // "Platform:" - same word in Turkish
            "version.title",        // "NexoSync"
            "version.nexo",         // "Nexo:"
            "version.platform",     // "Platform:"
            "version.minecraft",    // "Minecraft:"
            "version.java");        // "Java:"

    /**
     * Guards against a translator accidentally leaving an English sentence in place.
     */
    @Test
    @DisplayName("The Turkish file is actually translated")
    void turkishIsNotACopy() throws IOException {
        YamlConfiguration english = load(REFERENCE);
        YamlConfiguration turkish = load("tr_TR");

        List<String> identical = new ArrayList<>();
        for (String key : keysOf(REFERENCE)) {
            if (INTENTIONALLY_IDENTICAL.contains(key)) {
                continue;
            }
            String source = english.getString(key);
            String target = turkish.getString(key);
            if (source != null && source.equals(target)) {
                identical.add(key);
            }
        }

        assertTrue(identical.isEmpty(), () -> "these Turkish messages are still English: " + identical);
    }
}
