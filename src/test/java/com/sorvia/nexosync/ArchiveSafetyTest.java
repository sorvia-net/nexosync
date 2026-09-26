package com.sorvia.nexosync;

import com.sorvia.nexosync.util.FileUtils;
import com.sorvia.nexosync.util.Hashing;
import com.sorvia.nexosync.util.Zips;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers archive handling, which is where content downloaded from the internet first touches disk.
 *
 * <p>These are the checks that make a hostile or corrupt release survivable: an entry must never
 * resolve outside the staging directory, and neither the entry count nor the uncompressed size may
 * exceed what the operator allowed.</p>
 */
class ArchiveSafetyTest {

    @Test
    @DisplayName("Packaging and extraction preserve paths and content")
    void roundTrip(@TempDir Path work) throws IOException {
        Path source = Files.createDirectories(work.resolve("source"));
        Files.writeString(source.resolve("ruby.yml"), "material: DIAMOND_SWORD");
        Files.writeString(source.resolve("coin.yml"), "material: GOLD_NUGGET");

        Map<String, Path> entries = new LinkedHashMap<>();
        entries.put("items/ruby.yml", source.resolve("ruby.yml"));
        entries.put("pack/external_packs/coin.yml", source.resolve("coin.yml"));

        Path archive = work.resolve("snapshot.zip");
        Zips.create(archive, entries);

        Path target = work.resolve("staging");
        Zips.ExtractionResult result = Zips.extract(archive, target, 100, 10_000_000L);

        assertEquals(2, result.fileCount());
        assertEquals("material: DIAMOND_SWORD", Files.readString(target.resolve("items/ruby.yml")));
        assertEquals("material: GOLD_NUGGET", Files.readString(target.resolve("pack/external_packs/coin.yml")));
    }

    @Test
    @DisplayName("The same content always produces a byte-identical archive")
    void packagingIsDeterministic(@TempDir Path work) throws IOException {
        Path source = Files.createDirectories(work.resolve("source"));
        Files.writeString(source.resolve("a.yml"), "one");
        Files.writeString(source.resolve("b.yml"), "two");

        Map<String, Path> forward = new LinkedHashMap<>();
        forward.put("items/a.yml", source.resolve("a.yml"));
        forward.put("items/b.yml", source.resolve("b.yml"));

        Map<String, Path> reverse = new LinkedHashMap<>();
        reverse.put("items/b.yml", source.resolve("b.yml"));
        reverse.put("items/a.yml", source.resolve("a.yml"));

        Path first = work.resolve("first.zip");
        Path second = work.resolve("second.zip");
        Zips.create(first, forward);
        Zips.create(second, reverse);

        assertEquals(Hashing.sha256(first), Hashing.sha256(second));
    }

    @Test
    @DisplayName("An entry that climbs out of the staging directory aborts the extraction")
    void pathTraversalIsRejected(@TempDir Path work) throws IOException {
        Path archive = work.resolve("evil.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("../../server.properties"));
            zip.write("owned".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        Path target = work.resolve("staging");
        assertThrows(IOException.class, () -> Zips.extract(archive, target, 100, 1_000_000L));
        assertFalse(Files.exists(work.resolve("server.properties")), "nothing may be written outside the target");
    }

    @Test
    @DisplayName("An absolute entry path aborts the extraction")
    void absolutePathIsRejected(@TempDir Path work) throws IOException {
        Path archive = work.resolve("absolute.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("/etc/passwd"));
            zip.write("root".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        assertThrows(IOException.class, () -> Zips.extract(archive, work.resolve("staging"), 100, 1_000_000L));
    }

    @Test
    @DisplayName("The configured file count and size limits are enforced")
    void limitsAreEnforced(@TempDir Path work) throws IOException {
        Path archive = work.resolve("many.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (int i = 0; i < 20; i++) {
                zip.putNextEntry(new ZipEntry("items/file" + i + ".yml"));
                zip.write("content".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }

        assertThrows(IOException.class, () -> Zips.extract(archive, work.resolve("a"), 5, 1_000_000L),
            "too many entries must abort");
        assertThrows(IOException.class, () -> Zips.extract(archive, work.resolve("b"), 100, 8L),
            "too many uncompressed bytes must abort");
    }

    @Test
    @DisplayName("A single entry can be read without extracting the archive")
    void singleEntryRead(@TempDir Path work) throws IOException {
        Path source = Files.createDirectories(work.resolve("source"));
        Files.writeString(source.resolve("manifest.json"), "{\"schema_version\":1}");

        Path archive = work.resolve("snapshot.zip");
        Zips.create(archive, Map.of("manifest.json", source.resolve("manifest.json")));

        byte[] document = Zips.readEntry(archive, "manifest.json", 1_000_000L);
        assertEquals("{\"schema_version\":1}", new String(document, StandardCharsets.UTF_8));
        assertNull(Zips.readEntry(archive, "missing.json", 1_000_000L));
    }

    @Test
    @DisplayName("Relative paths that escape their root are refused")
    void unsafeRelativePaths() {
        assertTrue(FileUtils.isSafeRelativePath("items/ruby.yml"));
        assertTrue(FileUtils.isSafeRelativePath("pack/external_packs/custom/assets/pack.mcmeta"));

        assertFalse(FileUtils.isSafeRelativePath("../../server.properties"));
        assertFalse(FileUtils.isSafeRelativePath("items/../../secret.yml"));
        assertFalse(FileUtils.isSafeRelativePath("..\\..\\server.properties"));
        assertFalse(FileUtils.isSafeRelativePath("/etc/passwd"));
        assertFalse(FileUtils.isSafeRelativePath("C:/Windows/system.ini"));
        assertFalse(FileUtils.isSafeRelativePath("   "));
        assertFalse(FileUtils.isSafeRelativePath(null));
    }

    @Test
    @DisplayName("Containment is decided on normalised absolute paths")
    void containmentCheck(@TempDir Path work) {
        Path base = work.resolve("staging");

        assertTrue(FileUtils.isWithin(base, base.resolve("items/ruby.yml")));
        assertFalse(FileUtils.isWithin(base, base.resolve("../outside.yml")));
        assertFalse(FileUtils.isWithin(base, work.resolve("other/file.yml")));
    }
}
