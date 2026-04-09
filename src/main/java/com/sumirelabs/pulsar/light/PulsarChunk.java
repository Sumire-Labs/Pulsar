package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.api.ExtendedChunk;

/**
 * Internal extension of {@link ExtendedChunk} exposing nibble storage to the
 * Pulsar engine. Mixed into {@code net.minecraft.world.chunk.Chunk} by
 * {@code com.sumirelabs.pulsar.mixin.MixinChunk}.
 *
 * <p>Pulsar's scalar release stores one block-light nibble group and one
 * sky-light nibble group per chunk. Future RGB modes can extend this
 * interface with G/B groups in their own subpackage without breaking the
 * scalar contract.
 */
public interface PulsarChunk extends ExtendedChunk {

    /**
     * Sync Pulsar SWMR visible data → vanilla {@code blockLight}/{@code skyLight}
     * nibble arrays so that {@link net.minecraft.world.chunk.Chunk#getLightFor}
     * returns up-to-date values for renderers and chunk packets.
     */
    void pulsar$syncLightToVanilla();

    void pulsar$setLightReady(boolean ready);

    // Block light
    SWMRNibbleArray[] pulsar$getBlockNibbles();

    void pulsar$setBlockNibbles(SWMRNibbleArray[] nibbles);

    boolean[] pulsar$getBlockEmptinessMap();

    void pulsar$setBlockEmptinessMap(boolean[] emptinessMap);

    // Sky light
    SWMRNibbleArray[] pulsar$getSkyNibbles();

    void pulsar$setSkyNibbles(SWMRNibbleArray[] nibbles);

    boolean[] pulsar$getSkyEmptinessMap();

    void pulsar$setSkyEmptinessMap(boolean[] emptinessMap);
}
