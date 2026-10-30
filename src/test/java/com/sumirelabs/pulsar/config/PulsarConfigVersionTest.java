package com.sumirelabs.pulsar.config;

import net.minecraftforge.fml.relauncher.FMLInjectionData;
import net.minecraftforge.common.config.Configuration;
import com.sumirelabs.pulsar.core.RgbMixinSelection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PulsarConfigVersionTest {
    @TempDir Path directory;
    private Field injectionHome;
    private File previousHome;

    @BeforeEach
    void prepareForgeParser() throws Exception {
        // Configuration's path normalization expects FML's game directory.
        // Do not set Launch.minecraftHome: these tests must never run startup migration.
        injectionHome = FMLInjectionData.class.getDeclaredField("minecraftHome");
        injectionHome.setAccessible(true);
        previousHome = (File) injectionHome.get(null);
        injectionHome.set(null, directory.toFile());
    }

    @AfterEach
    void restoreForgeParser() throws Exception {
        injectionHome.set(null, previousHome);
    }

    @Test
    void newInstallationLeavesGenerationToForge() throws Exception {
        final Path file = directory.resolve("pulsar.cfg");
        assertFalse(PulsarConfigVersion.prepare(file.toFile(), 3));
        assertFalse(Files.exists(file));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 7})
    void matchingVersionPreservesCustomSettingsAndFileBytes(int threads) throws Exception {
        final Path file = write("I:configVersion=3\n", "I:experimentalServerLightThreads=" + threads);
        final byte[] original = Files.readAllBytes(file);
        assertFalse(PulsarConfigVersion.prepare(file.toFile(), 3));
        assertArrayEquals(original, Files.readAllBytes(file));
        // Repeated startup checks must not rewrite a user's same-version choices.
        assertFalse(PulsarConfigVersion.prepare(file.toFile(), 3));
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1, 2, 4, 99})
    void differentVersionsPreserveSettingsAndUpdateOnlyTheSchemaMarker(int version) throws Exception {
        final Path file = write("I:configVersion=" + version + "\n", "I:experimentalServerLightThreads=0");
        assertTrue(PulsarConfigVersion.prepare(file.toFile(), 3));
        assertPreserved(file);
    }

    @Test
    void versionlessLegacyConfigPreservesSettings() throws Exception {
        final Path file = write("# Pulsar Config Version 2\n", "I:experimentalServerLightThreads=0");
        assertTrue(PulsarConfigVersion.prepare(file.toFile(), 3));
        assertPreserved(file);
    }

    @ParameterizedTest
    @ValueSource(strings = {"I:configVersion=invalid", "S:configVersion=2"})
    void invalidVersionIsNotAcceptedAsCurrent(String property) throws Exception {
        final Path file = write(property + "\n", "I:experimentalServerLightThreads=0");
        assertTrue(PulsarConfigVersion.prepare(file.toFile(), 3));
        assertPreserved(file);
    }

    @Test
    void migrationDoesNotTouchOtherConfigFiles() throws Exception {
        final Path file = write("", "I:experimentalServerLightThreads=0");
        final Path other = directory.resolve("another-mod.cfg");
        Files.writeString(other, "keep");
        assertTrue(PulsarConfigVersion.prepare(file.toFile(), 3));
        assertEquals("keep", Files.readString(other));
    }

    @Test
    void refusesToRemoveDirectoryAtConfigPath() throws Exception {
        final Path file = Files.createDirectory(directory.resolve("pulsar.cfg"));
        Files.writeString(file.resolve("keep"), "keep");
        assertThrows(IOException.class, () -> PulsarConfigVersion.prepare(file.toFile(), 3));
        assertEquals("keep", Files.readString(file.resolve("keep")));
    }

    @Test
    void migrationPreservesModesListsUnknownOptionsAndNestedCategories() throws Exception {
        Path file=directory.resolve("pulsar.cfg");
        Files.writeString(file,"general {\n I:configVersion=2\n S:lightingMode=RGB\n S:rgbGameplayLight=VANILLA\n B:enabled=false\n performance {\n I:tileEntityLightBudgetMicros=2345\n }\n}\ncustom {\n S:labels <\n first\n second\n >\n}\n");
        assertTrue(RgbMixinSelection.selectsRgb(Files.readString(file)));
        assertTrue(PulsarConfigVersion.prepare(file.toFile(),3));
        Configuration config=new Configuration(file.toFile());
        assertEquals("RGB",config.getCategory("general").get("lightingMode").getString());
        assertEquals("VANILLA",config.getCategory("general").get("rgbGameplayLight").getString());
        assertFalse(config.getCategory("general").get("enabled").getBoolean());
        assertEquals(2345,config.getCategory("general.performance").get("tileEntityLightBudgetMicros").getInt());
        assertArrayEquals(new String[]{"first","second"},config.getCategory("custom").get("labels").getStringList());
        assertTrue(RgbMixinSelection.selectsRgb(Files.readString(file)));
        byte[] migrated=Files.readAllBytes(file);
        assertFalse(PulsarConfigVersion.prepare(file.toFile(),3));
        assertArrayEquals(migrated,Files.readAllBytes(file));
    }

    @Test
    void malformedConfigRegeneratesInsteadOfRetainingPartialRgbSettings() throws Exception {
        Path file=directory.resolve("pulsar.cfg");
        Files.writeString(file,"general {\n I:configVersion=2\n S:lightingMode=RGB\n }\n }\n");
        assertTrue(PulsarConfigVersion.prepare(file.toFile(),3));
        Configuration config=new Configuration(file.toFile());
        assertEquals(3,config.getCategory("general").get("configVersion").getInt());
        assertFalse(config.hasKey("general","lightingMode"));
        assertFalse(RgbMixinSelection.selectsRgb(Files.readString(file)));
        assertFalse(PulsarConfigVersion.prepare(file.toFile(),3));
    }

    private void assertPreserved(Path file) throws IOException {
        Configuration config=new Configuration(file.toFile());
        assertEquals(3,config.getCategory("general").get("configVersion").getInt());
        assertEquals(0,config.getCategory("general.features").get("experimentalServerLightThreads").getInt());
        assertFalse(PulsarConfigVersion.prepare(file.toFile(),3));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void earlyMixinSelectionMatchesMigratedOrRegeneratedSettings(boolean malformed) throws Exception {
        Path configDirectory=Files.createDirectories(directory.resolve("config"));
        Path file=configDirectory.resolve("pulsar.cfg");
        Files.writeString(file,"general {\nI:configVersion=2\nS:lightingMode=RGB\n}\n"+(malformed?"}\n":""));
        var previousHome=net.minecraft.launchwrapper.Launch.minecraftHome;
        var enabled=RgbMixinSelection.class.getDeclaredField("enabled");enabled.setAccessible(true);
        boolean previousEnabled=enabled.getBoolean(null);
        try {
            net.minecraft.launchwrapper.Launch.minecraftHome=directory.toFile();
            new RgbMixinSelection().onLoad("com.sumirelabs.pulsar.mixin");
            assertEquals(!malformed,RgbMixinSelection.rgbEnabled());
            assertEquals(3,new Configuration(file.toFile()).getCategory("general").get("configVersion").getInt());
        } finally {
            net.minecraft.launchwrapper.Launch.minecraftHome=previousHome;
            enabled.setBoolean(null,previousEnabled);
        }
    }

    private Path write(String version, String setting) throws IOException {
        final Path file = directory.resolve("pulsar.cfg");
        Files.writeString(file, "general {\n" + version
                + "features {\n" + setting + "\n}\n}\n");
        return file;
    }
}
