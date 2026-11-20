package com.sorvia.nexosync.snapshot;

import com.sorvia.nexosync.config.NexoSyncConfig;
import com.sorvia.nexosync.log.NexoSyncLogger;
import com.sorvia.nexosync.util.FileUtils;
import com.sorvia.nexosync.util.SecretMask;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers single files listed under {@code sync.paths}, such as {@code inventory.yml}.
 *
 * <p>Every managed path used to be assumed to be a directory. A file entry was rejected outright on
 * publish, and on a receiver the installer would have created a <em>folder</em> named after it,
 * after which the real file could never be written. These tests pin the file-aware behaviour.</p>
 */
class ManagedFileTest {

    @Test
    @DisplayName("An existing file is recognised as a file, an existing directory as a directory")
    void diskStateDecides(@TempDir Path nexo) throws Exception {
        Files.writeString(nexo.resolve("inventory.yml"), "menu: {}\n");
        Files.createDirectories(nexo.resolve("items"));

        assertTrue(FileUtils.isManagedFile(nexo.resolve("inventory.yml"), "inventory.yml"));
        assertFalse(FileUtils.isManagedFile(nexo.resolve("items"), "items"));
    }

    @Test
    @DisplayName("A path that does not exist yet is judged by its name")
    void nameDecidesWhenAbsent(@TempDir Path nexo) {
        assertTrue(FileUtils.isManagedFile(nexo.resolve("inventory.yml"), "inventory.yml"));
        assertTrue(FileUtils.isManagedFile(nexo.resolve("pack/pack.mcmeta"), "pack/pack.mcmeta"));
        assertFalse(FileUtils.isManagedFile(nexo.resolve("items"), "items"));
        assertFalse(FileUtils.isManagedFile(nexo.resolve("pack/external_packs"), "pack/external_packs"));
    }

    @Test
    @DisplayName("A listed file is published under its own name, next to directory contents")
    void fileIsCollectedAsOneEntry(@TempDir Path nexo) throws Exception {
        Files.createDirectories(nexo.resolve("items"));
        Files.writeString(nexo.resolve("items/ruby.yml"), "ruby: {}\n");
        Files.writeString(nexo.resolve("inventory.yml"), "menu: {}\n");

        SnapshotService service = new SnapshotService(
                configWithPaths(List.of("items", "inventory.yml")), nexo,
                new NexoSyncLogger(Logger.getLogger("ManagedFileTest"), new SecretMask()));

        SnapshotService.Collected collected = service.collect("test");

        // Order is irrelevant here: the snapshot hash and the archive both sort entries themselves.
        assertEquals(Set.of("inventory.yml", "items/ruby.yml"), collected.files().keySet());
        assertEquals(2, collected.entries().size());
    }

    private static NexoSyncConfig configWithPaths(List<String> paths) throws Exception {
        try (InputStream in = ManagedFileTest.class.getClassLoader().getResourceAsStream("config.yml")) {
            assertNotNull(in, "config.yml is missing from src/main/resources");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            yaml.set("sync.paths", paths);
            return NexoSyncConfig.load(yaml);
        }
    }
}
