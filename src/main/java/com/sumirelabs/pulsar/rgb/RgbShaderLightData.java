/* SPDX-License-Identifier: LGPL-3.0-only */
package com.sumirelabs.pulsar.rgb;

/** Original CPU/GPU data contract. Material color and lightmap UVs are not repurposed. */
public final class RgbShaderLightData {
    public static final int CELLS = 4096;
    public static final int DATA_WIDTH = 4096;
    public static final int INDEX_WIDTH = 256;
    public static final int MISSING = -1, CONSTANT = -2;
    private RgbShaderLightData() {}

    public static int pack(int blockRgb, int skyRgb, int blockCanonical, int skyCanonical) {
        return blockRgb & 0xFFF | (skyRgb & 0xFFF) << 12
                | (blockCanonical & 15) << 24 | (skyCanonical & 15) << 28;
    }
    public static int block(int value) { return value & 0xFFF; }
    public static int sky(int value) { return value >>> 12 & 0xFFF; }
    public static int blockCanonical(int value) { return value >>> 24 & 15; }
    public static int skyCanonical(int value) { return value >>> 28; }
    static int fromCells(int block, int sky) {
        return block & 0xFFF | (sky & 0xFFF) << 12 | (block & 0xF000) << 12 | (sky & 0xF000) << 16;
    }

    /** Same independent-component maximum stencil used by the CPU color sampler. */
    public static int max(int a, int b) {
        int result = 0;
        for (int shift = 0; shift < 32; shift += 4)
            result |= Math.max(a >>> shift & 15, b >>> shift & 15) << shift;
        return result;
    }

    public static int cell(int x, int y, int z) { return x & 15 | (z & 15) << 4 | (y & 15) << 8; }

    public record Layout(int side, int minSection, int sectionCount) {
        public Layout {
            if (side < 1 || side > 128 || (side & side - 1) != 0 || sectionCount < 1 || sectionCount > 4098)
                throw new IllegalArgumentException("Invalid shader light layout");
        }
        public int entries() { return side * side * sectionCount; }
        public int index(int chunkX, int sectionY, int chunkZ) {
            int section = sectionY - minSection;
            if (section < 0 || section >= sectionCount) return -1;
            return (chunkX & side - 1) + (chunkZ & side - 1) * side + section * side * side;
        }
        public boolean owns(int queriedX, int queriedZ, int recordedX, int recordedZ) {
            return queriedX == recordedX && queriedZ == recordedZ;
        }
    }
}
