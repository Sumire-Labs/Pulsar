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
     * @return the chunk if registered with Pulsar, {@code null} otherwise
     * (including worlds without a Pulsar manager). Never triggers chunk loading.
     */
    Chunk pulsar$getAnyChunkImmediately(int chunkX, int chunkZ);

    /**
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @return whether either lighting queue currently has work for this chunk,
     * including ordinary updates. This is not an initial-light-only query and
     * a false result does not guarantee that neighbouring work, contextual
     * sampling, network delivery or rendering has completed.
     */
    boolean pulsar$hasChunkPendingLight(int chunkX, int chunkZ);

    /**
     * Legacy placeholder retained for binary compatibility. The current
     * implementation is a no-op: it neither drains queues nor waits for light.
     *
     * @deprecated No completion guarantee. Do not use as a synchronization barrier.
     */
    @Deprecated
    void pulsar$flushLightUpdates();
}
