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

    /** Lifecycle callbacks may race a worker. Implementations must reject stale publications. */
    default void chunkLoaded(Chunk chunk) {}
    default void chunkUnloaded(int chunkX, int chunkZ) {}

    /** Called before block work is enqueued; invalidate additional data before a worker can publish it. */
    default void blockLightQueued(int chunkX, int chunkZ) {}

    /** Position-specific checks need not invalidate unrelated heights in an extension's region. */
    default void blockLightQueuedAt(int x, int y, int z) { blockLightQueued(x >> 4, z >> 4); }

    /**
     * Successful block-state writes can change RGB without changing scalar emission or opacity.
     * Return true to request BLOCK work even when vanilla would omit its light check.
     */
    default boolean needsBlockStateUpdate(int x, int y, int z) { return false; }

    /**
     * Runs on the block lane after successful scalar work and before its completion signal.
     * Additional channels have their own availability state: this callback must not mark
     * scalar chunks ready. It must not wait on the current lane or load missing chunks.
     */
    default void afterBlockTask(int chunkX, int chunkZ) {}

    /** Stop accepting publications, including from a worker that exceeded the shutdown join timeout. */
    default void close() {}

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
