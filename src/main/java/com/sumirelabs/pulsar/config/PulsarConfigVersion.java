package com.sumirelabs.pulsar.config;

import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/** Runs before Forge injects settings, so old values cannot become new defaults. */
final class PulsarConfigVersion {
    private PulsarConfigVersion() {}

    static boolean prepare(final File file, final int expectedVersion) throws IOException {
        if (!Files.exists(file.toPath())) return false;
        if (!Files.isRegularFile(file.toPath())) {
            throw new IOException("Pulsar config is not a regular file: " + file);
        }
        final Configuration config = new Configuration(file);
        if (config.hasKey(Configuration.CATEGORY_GENERAL, "configVersion")) {
            final Property version = config.getCategory(Configuration.CATEGORY_GENERAL).get("configVersion");
            if (!version.isList() && version.getType() == Property.Type.INTEGER
                    && version.isIntValue() && version.getInt() == expectedVersion) {
                return false;
            }
        }
        // Only this mod's config is replaced. Forge subsequently writes all current defaults.
        Files.deleteIfExists(file.toPath());
        return true;
    }
}
