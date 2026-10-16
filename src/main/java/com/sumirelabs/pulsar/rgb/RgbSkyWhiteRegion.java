package com.sumirelabs.pulsar.rgb;

/** A component can travel at most 14 blocks horizontally, hence one chunk.
 * Proving the center 3x3 white needs the surrounding 5x5 to be filter-free. */
public final class RgbSkyWhiteRegion {
    @FunctionalInterface public interface Lookup { boolean filtered(int x, int z); }
    private RgbSkyWhiteRegion() {}
    public static boolean canUseWhite(int x, int z, Lookup lookup) {
        for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++)
            if (lookup.filtered(x + dx, z + dz)) return false;
        return true;
    }
    public static boolean contains(int x, int z, int chunkX, int chunkZ) {
        return Math.abs((long)x - chunkX) <= 1 && Math.abs((long)z - chunkZ) <= 1;
    }
}
