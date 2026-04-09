package com.sumirelabs.pulsar.api;

/**
 * Pulsar extension of {@code net.minecraft.world.chunk.Chunk}, mixed in at
 * runtime by {@code com.sumirelabs.pulsar.mixin.MixinChunk}.
 *
 * <p>Other mods may cast a {@link net.minecraft.world.chunk.Chunk} to this
 * interface to query whether Pulsar's BFS lighting engine has finished its
 * initial pass for the chunk. Light values returned from
 * {@link net.minecraft.world.chunk.Chunk#getLightFor} before
 * {@link #pulsar$isLightReady()} returns {@code true} should be treated as
 * provisional.
 */
public interface ExtendedChunk {

    /**
     * @return {@code true} once Pulsar has completed initial light propagation
     *         for this chunk.
     */
    boolean pulsar$isLightReady();
}
