package com.sumirelabs.pulsar.config;

import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Objects;

/** Saves the mode and first-run acknowledgement together without replacing other settings. */
public final class LightingModeSelection {
    private LightingModeSelection() {}

    public static void save(File file, PulsarConfig.LightingMode mode) throws IOException {
        Objects.requireNonNull(mode, "mode");
        final var path = file.toPath().toAbsolutePath();
        Files.createDirectories(path.getParent());
        final var staged = Files.createTempFile(path.getParent(), "pulsar-mode-", ".cfg");
        try {
            final byte[] original = Files.exists(path) ? Files.readAllBytes(path) : new byte[0];
            Files.write(staged, original);
            final Configuration config = new Configuration(staged.toFile());
            if (!Arrays.equals(original, Files.readAllBytes(staged))) {
                throw new IOException("Pulsar config was edited with invalid syntax; cannot save lighting mode");
            }
            final var general = config.getCategory(Configuration.CATEGORY_GENERAL);
            general.put("configVersion", new Property("configVersion",
                    Integer.toString(PulsarConfig.CURRENT_CONFIG_VERSION), Property.Type.INTEGER));
            general.put("lightingMode", new Property("lightingMode", mode.name(), Property.Type.STRING));
            general.put("lightingModeSelected", new Property("lightingModeSelected", "true", Property.Type.BOOLEAN));
            config.save();
            // Forge logs save errors instead of throwing; verify before replacing the live file.
            final Configuration saved = new Configuration(staged.toFile());
            if (!saved.hasKey("general", "lightingMode")
                    || !mode.name().equals(saved.getCategory("general").get("lightingMode").getString())
                    || !saved.hasKey("general", "lightingModeSelected")
                    || !saved.getCategory("general").get("lightingModeSelected").getBoolean()) {
                throw new IOException("Cannot save Pulsar lighting mode: " + file);
            }
            Files.move(staged, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (RuntimeException failure) {
            throw new IOException("Cannot save Pulsar lighting mode: " + file, failure);
        } finally {
            Files.deleteIfExists(staged);
        }
    }
}
