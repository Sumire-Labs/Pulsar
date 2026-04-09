package com.sumirelabs.pulsar.util;

/**
 * Coordinate packing helpers used by the BFS engine and queue structures.
 *
 * <p>Ported as-is from SuperNova 1.7.10 — these utilities are version-agnostic.
 */
public final class CoordinateUtils {

    private CoordinateUtils() {}

    /**
     * Pack a chunk (x, z) pair into a single 64-bit key. Lower 32 bits hold
     * the X coordinate (signed), upper 32 bits hold the Z coordinate.
     */
    public static long getChunkKey(final int x, final int z) {
        return ((long) z << 32) | (x & 0xFFFFFFFFL);
    }

    public static int getChunkX(final long chunkKey) {
        return (int) chunkKey;
    }

    public static int getChunkZ(final long chunkKey) {
        return (int) (chunkKey >>> 32);
    }
}
