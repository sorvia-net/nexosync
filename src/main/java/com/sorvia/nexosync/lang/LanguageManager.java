package com.sorvia.nexosync.lang;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads and renders the external language file.
 *
 * <p>All built-in text lives in {@code lang/en_US.yml}, which is written to the data folder on first
 * start and never overwritten afterwards. Messages use MiniMessage markup and {@code {placeholder}}
 * tokens; placeholder values are stripped of markup characters before substitution so that a file
 * name can never inject formatting into a message.</p>
 */
public final class LanguageManager {

    private static final String DEFAULT_LANGUAGE = "en_US";

    /**
     * Languages shipped inside the JAR. Each one is written to the data folder on first start so an
     * administrator can see - and edit - every available translation without unpacking the plugin.
     */
    private static final List<String> BUNDLED_LANGUAGES = List.of("en_US", "tr_TR");

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final JavaPlugin plugin;

    private YamlConfiguration messages = new YamlConfiguration();
    private YamlConfiguration fallback = new YamlConfiguration();
    private String prefix = "";

    public LanguageManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Writes the bundled default language file if it is missing, then loads the requested language.
     * An unknown or unreadable language silently falls back to {@code en_US}.
     */
    public void load(String language) {
        Path languageDirectory = plugin.getDataFolder().toPath().resolve("lang");
        try {
            Files.createDirectories(languageDirectory);
            for (String bundled : BUNDLED_LANGUAGES) {
                exportBundled(languageDirectory, bundled);
            }
        } catch (IOException exportFailed) {
            plugin.getLogger().warning("Unable to write the bundled language files: " + exportFailed.getMessage());
        }

        this.fallback = loadBundled(DEFAULT_LANGUAGE);

        String requested = language == null || language.isBlank() ? DEFAULT_LANGUAGE : language.trim();
        Path file = languageDirectory.resolve(requested + ".yml");

        if (!Files.isRegularFile(file)) {
            if (!requested.equals(DEFAULT_LANGUAGE)) {
                plugin.getLogger().warning("Language file " + requested + ".yml not found, using " + DEFAULT_LANGUAGE + ".");
            }
            file = languageDirectory.resolve(DEFAULT_LANGUAGE + ".yml");
        }

        this.messages = Files.isRegularFile(file)
                ? YamlConfiguration.loadConfiguration(file.toFile())
                : new YamlConfiguration();

        this.prefix = raw("prefix", "");
    }

    private void exportBundled(Path directory, String language) throws IOException {
        Path target = directory.resolve(language + ".yml");
        if (Files.exists(target)) {
            return;
        }
        try (InputStream in = plugin.getResource("lang/" + language + ".yml")) {
            if (in != null) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private YamlConfiguration loadBundled(String language) {
        try (InputStream in = plugin.getResource("lang/" + language + ".yml")) {
            if (in == null) {
                return new YamlConfiguration();
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException readFailed) {
            return new YamlConfiguration();
        }
    }

    // -----------------------------------------------------------------------------------------
    // Rendering
    // -----------------------------------------------------------------------------------------

    /**
     * Returns the raw message text for a key, falling back to the bundled English file and finally
     * to the key itself so a missing entry is visible rather than silent.
     */
    public String raw(String key, String defaultValue) {
        String value = messages.getString(key);
        if (value == null) {
            value = fallback.getString(key);
        }
        return value == null ? defaultValue : value;
    }

    public Component component(String key, Object... placeholders) {
        return MINI_MESSAGE.deserialize(substitute(raw(key, key), placeholders));
    }

    public Component prefixed(String key, Object... placeholders) {
        return MINI_MESSAGE.deserialize(prefix + substitute(raw(key, key), placeholders));
    }

    public String plain(String key, Object... placeholders) {
        return PlainTextComponentSerializer.plainText().serialize(component(key, placeholders));
    }

    public void send(CommandSender sender, String key, Object... placeholders) {
        sender.sendMessage(prefixed(key, placeholders));
    }

    public void sendRaw(CommandSender sender, String key, Object... placeholders) {
        sender.sendMessage(component(key, placeholders));
    }

    /**
     * Renders a snapshot version for display, translating the "nothing installed yet" case.
     *
     * <p>Console and Discord keep the untranslated form on purpose - those are read by whoever is
     * debugging, not necessarily by the operator who set the server language.</p>
     */
    public String versionLabel(int version) {
        return version > 0 ? "v" + version : plain("general.no-snapshot");
    }

    /**
     * Replaces {@code {name}} tokens. Values are sanitised so that user or file supplied content
     * cannot introduce MiniMessage tags.
     */
    private static String substitute(String template, Object... placeholders) {
        if (placeholders == null || placeholders.length == 0) {
            return template;
        }
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            String key = String.valueOf(placeholders[i]);
            String value = placeholders[i + 1] == null ? "" : String.valueOf(placeholders[i + 1]);
            values.put(key, value.replace('<', '(').replace('>', ')'));
        }

        String result = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }
}
