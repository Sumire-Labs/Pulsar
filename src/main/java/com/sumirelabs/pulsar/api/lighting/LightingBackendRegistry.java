package com.sumirelabs.pulsar.api.lighting;

import com.sumirelabs.pulsar.util.WorldHeightContext;
import net.minecraft.world.World;
import java.util.Objects;

/** One backend provider per JVM; register during mod initialization, before any world starts. */
public final class LightingBackendRegistry {
    @FunctionalInterface
    public interface Provider {
        /** Return null when the addon is disabled for this world. */
        WorldLightingBackend create(World world, WorldHeightContext height);
    }
    private static Provider provider;
    private static boolean frozen;
    private LightingBackendRegistry() {}

    public static synchronized void register(Provider candidate) {
        Objects.requireNonNull(candidate, "provider");
        if (frozen) throw new IllegalStateException("Lighting backend registration is closed: a world has already started");
        if (provider != null) throw new IllegalStateException("A lighting backend is already registered");
        provider = candidate;
    }

    public static WorldLightingBackend create(World world, WorldHeightContext height) {
        Provider selected;
        synchronized (LightingBackendRegistry.class) { frozen = true; selected = provider; }
        return selected == null ? null : selected.create(world, height);
    }
}
