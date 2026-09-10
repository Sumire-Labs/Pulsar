package com.sumirelabs.pulsar.api.lighting;

import com.sumirelabs.pulsar.light.engine.PulsarEngine;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.chunk.Chunk;
import java.util.function.Supplier;

/**
 * Experimental, startup-selected world lighting extension. Factories use Pulsar's
 * existing workers and queues. A null factory retains that lane's scalar engine.
 * The backend owns additional channel storage, packets and rendering; registering
 * an RGB factory alone does not implement those responsibilities.
 */
public interface WorldLightingBackend {
    /** Stable namespaced format/configuration identity, frozen for this world. */
    String cacheKey();
    default Supplier<PulsarEngine> blockEngineFactory() { return null; }
    default Supplier<PulsarEngine> skyEngineFactory() { return null; }

    /**
     * Attach additional data beneath the supplied PulsarLight compound. Return false
     * to omit the entire saved-light cache. Called only after ordinary readiness checks.
     */
    default boolean saveLight(Chunk chunk, NBTTagCompound lightTag) { return false; }

    /**
     * Validate and restore all additional data, publishing atomically. Returning false
     * rejects the entire cache and requests ordinary relighting. Never mark a chunk ready.
     */
    default boolean loadLight(Chunk chunk, NBTTagCompound lightTag) { return false; }
}
