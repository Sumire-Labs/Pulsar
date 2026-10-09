package com.sumirelabs.pulsar.config;

import net.minecraftforge.fml.relauncher.FMLInjectionData;
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
        assertFalse(PulsarConfigVersion.prepare(file.toFile(), 2));
        assertFalse(Files.exists(file));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 7})
    void matchingVersionPreservesCustomSettingsAndFileBytes(int threads) throws Exception {
        final Path file = write("I:configVersion=2\n", "I:experimentalServerLightThreads=" + threads);
        final byte[] original = Files.readAllBytes(file);
        assertFalse(PulsarConfigVersion.prepare(file.toFile(), 2));
        assertArrayEquals(original, Files.readAllBytes(file));
        // Repeated startup checks must not rewrite a user's same-version choices.
        assertFalse(PulsarConfigVersion.prepare(file.toFile(), 2));
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1, 3, 99})
    void anyDifferentVersionRequiresAllSettingsToBeRegenerated(int version) throws Exception {
        final Path file = write("I:configVersion=" + version + "\n", "I:experimentalServerLightThreads=0");
        assertTrue(PulsarConfigVersion.prepare(file.toFile(), 2));
        assertFalse(Files.exists(file));
    }

    @Test
    void versionlessLegacyConfigResetsEvenIfCommentMentionsVersionTwo() throws Exception {
        final Path file = write("# Pulsar Config Version 2\n", "I:experimentalServerLightThreads=0");
        assertTrue(PulsarConfigVersion.prepare(file.toFile(), 2));
        assertFalse(Files.exists(file));
    }

    @ParameterizedTest
    @ValueSource(strings = {"I:configVersion=invalid", "S:configVersion=2"})
    void invalidVersionIsNotAcceptedAsCurrent(String property) throws Exception {
        final Path file = write(property + "\n", "I:experimentalServerLightThreads=0");
        assertTrue(PulsarConfigVersion.prepare(file.toFile(), 2));
        assertFalse(Files.exists(file));
    }

    @Test
    void regenerationDoesNotTouchOtherConfigFiles() throws Exception {
        final Path file = write("", "I:experimentalServerLightThreads=0");
        final Path other = directory.resolve("another-mod.cfg");
        Files.writeString(other, "keep");
        assertTrue(PulsarConfigVersion.prepare(file.toFile(), 2));
        assertEquals("keep", Files.readString(other));
    }

    @Test
    void refusesToRemoveDirectoryAtConfigPath() throws Exception {
        final Path file = Files.createDirectory(directory.resolve("pulsar.cfg"));
        Files.writeString(file.resolve("keep"), "keep");
        assertThrows(IOException.class, () -> PulsarConfigVersion.prepare(file.toFile(), 2));
        assertEquals("keep", Files.readString(file.resolve("keep")));
    }

    private Path write(String version, String setting) throws IOException {
        final Path file = directory.resolve("pulsar.cfg");
        Files.writeString(file, "general {\n" + version
                + "features {\n" + setting + "\n}\n}\n");
        return file;
    }
}
