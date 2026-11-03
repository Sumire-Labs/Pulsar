package com.sumirelabs.pulsar.light;

/** Common-side packet metadata; vanilla coordinate getters are stripped on dedicated servers. */
public interface ChunkPacketCoordinates {
    int pulsar$chunkX();
    int pulsar$chunkZ();
    boolean pulsar$fullChunk();
}
