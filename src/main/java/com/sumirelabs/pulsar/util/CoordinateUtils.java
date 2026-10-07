package com.sumirelabs.pulsar.util;

/**
 * Coordinate packing helpers used by the BFS engine and queue structures.
 *
 * <p>Coordinate packing is inherited from SuperNova 1.7.10. Hash-map key
 * spreading keeps that external coordinate format unchanged.
 */
public final class CoordinateUtils {

    private CoordinateUtils() {
    }

    /**
     * Pack a chunk (x, z) pair into a single 64-bit key. Lower 32 bits hold
     * the X coordinate (signed), upper 32 bits hold the Z coordinate.
     */
    public static long getChunkKey(final int x, final int z) {
        return ((long) z << 32) | (x & 0xFFFFFFFFL);
    }

    /**
     * Reversibly spreads the packed coordinates before using them as a boxed
     * {@link Long} hash-map key. {@link Long#hashCode()} folds the two halves
     * with XOR, so raw packed keys have identical hashes whenever {@code x ^ z}
     * matches. The SplitMix64 finalizer uses only bijective operations and
     * leaves the coordinate key itself untouched for queues and serialization.
     */
    public static long mixChunkKey(final long chunkKey) {
        long mixed = chunkKey;
        mixed ^= mixed >>> 30;
        mixed *= 0xBF58476D1CE4E5B9L;
        mixed ^= mixed >>> 27;
        mixed *= 0x94D049BB133111EBL;
        mixed ^= mixed >>> 31;
        return mixed;
    }

    public static int getChunkX(final long chunkKey) {
        return (int) chunkKey;
    }

    public static int getChunkZ(final long chunkKey) {
        return (int) (chunkKey >>> 32);
    }
}
