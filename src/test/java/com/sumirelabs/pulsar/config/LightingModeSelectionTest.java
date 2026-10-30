package com.sumirelabs.pulsar.config;

import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.relauncher.FMLInjectionData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LightingModeSelectionTest {
    @TempDir Path directory;
    private Field home;
    private File previousHome;

    @BeforeEach void prepareForge() throws Exception {
        home = FMLInjectionData.class.getDeclaredField("minecraftHome");
        home.setAccessible(true);
        previousHome = (File) home.get(null);
        home.set(null, directory.toFile());
    }

    @AfterEach void restoreForge() throws Exception {
        home.set(null, previousHome);
    }

    @ParameterizedTest
    @EnumSource(PulsarConfig.LightingMode.class)
    void selectionSurvivesReloadAndPreservesMigratedOptions(PulsarConfig.LightingMode mode) throws Exception {
        Path file = directory.resolve("pulsar.cfg");
        Files.writeString(file, "general {\n I:configVersion=2\n S:lightingMode=RGB\n"
                + " S:rgbGameplayLight=VANILLA\n B:enabled=false\n performance {\n"
                + " I:tileEntityLightBudgetMicros=2345\n }\n}\n"
                + "custom {\n S:labels <\n first\n second\n >\n}\n");
        PulsarConfigVersion.prepare(file.toFile(), 3);
        LightingModeSelection.save(file.toFile(), mode);
        Configuration saved = new Configuration(file.toFile());
        assertEquals(mode.name(), saved.getCategory("general").get("lightingMode").getString());
        assertTrue(saved.getCategory("general").get("lightingModeSelected").getBoolean());
        assertEquals(3, saved.getCategory("general").get("configVersion").getInt());
        assertEquals("VANILLA", saved.getCategory("general").get("rgbGameplayLight").getString());
        assertFalse(saved.getCategory("general").get("enabled").getBoolean());
        assertEquals(2345, saved.getCategory("general.performance").get("tileEntityLightBudgetMicros").getInt());
        assertArrayEquals(new String[]{"first", "second"},
                saved.getCategory("custom").get("labels").getStringList());
        byte[] original = Files.readAllBytes(file);
        assertFalse(PulsarConfigVersion.prepare(file.toFile(), 3));
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test void firstChoiceCreatesMissingConfigDirectoryAndCanBeChanged() throws Exception {
        File file = directory.resolve("config/pulsar.cfg").toFile();
        LightingModeSelection.save(file, PulsarConfig.LightingMode.SCALAR);
        LightingModeSelection.save(file, PulsarConfig.LightingMode.RGB);
        Configuration saved = new Configuration(file);
        assertEquals("RGB", saved.getCategory("general").get("lightingMode").getString());
        assertTrue(saved.getCategory("general").get("lightingModeSelected").getBoolean());
    }

    @Test void malformedExternalEditIsNotOverwrittenOrAcknowledged() throws Exception {
        Path file = directory.resolve("pulsar.cfg");
        Files.writeString(file, "general {\n B:lightingModeSelected=false\n}\n}\n");
        byte[] original = Files.readAllBytes(file);
        assertThrows(IOException.class, () -> LightingModeSelection.save(file.toFile(), PulsarConfig.LightingMode.RGB));
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test void unwritableConfigTargetDoesNotDiscardExistingFiles() throws Exception {
        Path file = Files.createDirectory(directory.resolve("pulsar.cfg"));
        Files.writeString(file.resolve("keep"), "keep");
        assertThrows(IOException.class, () -> LightingModeSelection.save(file.toFile(), PulsarConfig.LightingMode.RGB));
        assertEquals("keep", Files.readString(file.resolve("keep")));
        try (var paths = Files.list(directory)) {
            assertEquals(1, paths.count(), "No partial selection file should remain");
        }
    }
}
