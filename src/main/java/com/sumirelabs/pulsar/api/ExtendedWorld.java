package com.sumirelabs.pulsar.api;

import net.minecraft.world.chunk.Chunk;

/**
 * Pulsar extension of {@code net.minecraft.world.World}, mixed in at runtime
 * by {@code com.sumirelabs.pulsar.mixin.MixinWorld}.
 *
 * <p>Other mods may cast a {@link net.minecraft.world.World} to this interface
 * to coordinate gameplay logic with Pulsar's asynchronous BFS lighting (e.g.
 * delaying mob spawning until a chunk's initial light pass has completed).
 */
public interface ExtendedWorld {

    /**
     * @param chunkX chunk X coordinate (block X &gt;&gt; 4)
     * @param chunkZ chunk Z coordinate (block Z &gt;&gt; 4)
     * @return the chunk if loaded, {@code null} otherwise. Never triggers chunk
     *         loading.
     */
    Chunk pulsar$getAnyChunkImmediately(int chunkX, int chunkZ);

    /**
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @return {@code true} if the chunk is registered with Pulsar's
     *         {@code WorldLightManager} but its initial BFS pass has not yet
     *         completed.
     */
    boolean pulsar$hasChunkPendingLight(int chunkX, int chunkZ);

    /**
     * Block until all queued light updates for this world have been processed
     * by the worker threads. Useful for tooling and tests; gameplay code
     * should not normally need to call this.
     */
    void pulsar$flushLightUpdates();
}
