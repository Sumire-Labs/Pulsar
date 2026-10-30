package com.sumirelabs.pulsar.config;

import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/** Updates the schema marker before Forge fills in missing settings from current defaults. */
public final class PulsarConfigVersion {
    private PulsarConfigVersion() {}

    public static boolean prepare(final File file, final int expectedVersion) throws IOException {
        if (!Files.exists(file.toPath())) return false;
        if (!Files.isRegularFile(file.toPath())) {
            throw new IOException("Pulsar config is not a regular file: " + file);
        }
        final var path = file.toPath().toAbsolutePath();
        final byte[] original = Files.readAllBytes(path);
        final var staged = Files.createTempFile(path.getParent(), "pulsar-migration-", ".cfg");
        try {
            Files.write(staged, original);
            Configuration config = new Configuration(staged.toFile());
            // Forge replaces malformed input while loading. Discard any partial
            // values too, then let ConfigManager populate the current defaults.
            if (!Arrays.equals(original, Files.readAllBytes(staged))) {
                Files.write(staged, new byte[0]);
                config = new Configuration(staged.toFile());
                org.apache.logging.log4j.LogManager.getLogger("Pulsar Config").warn(
                        "Malformed Pulsar config {}; regenerating with current defaults", file);
            }
            if (matches(config, expectedVersion)) return false;
            config.getCategory(Configuration.CATEGORY_GENERAL).put("configVersion",
                    new Property("configVersion", Integer.toString(expectedVersion), Property.Type.INTEGER));
            config.save();
            if (!matches(new Configuration(staged.toFile()), expectedVersion))
                throw new IOException("Cannot write Pulsar config version: " + file);
            Files.move(staged, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (RuntimeException failure) {
            throw new IOException("Cannot migrate Pulsar config: " + file, failure);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static boolean matches(Configuration config, int expectedVersion) {
        if (config.hasKey(Configuration.CATEGORY_GENERAL, "configVersion")) {
            final Property version = config.getCategory(Configuration.CATEGORY_GENERAL).get("configVersion");
            if (!version.isList() && version.getType() == Property.Type.INTEGER
                    && version.isIntValue() && version.getInt() == expectedVersion) {
                return true;
            }
        }
        return false;
    }
}
