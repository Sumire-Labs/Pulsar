package com.sumirelabs.pulsar.rgb;

/** Keeps the unlit lightmap ambient when tinting RGB light; it is not source emission. */
public final class RgbAmbientFloor {

    private static volatile int[] lightmap;
    private RgbAmbientFloor() {}
    public static void update(int[] colors) {
        lightmap = colors != null && colors.length == 256 ? colors.clone() : null;
    }
    public static int preserve(int material, int tinted, float block, float sky) {
        int[] map = lightmap;
        if (map == null)
            return tinted;
        int result = tinted & 0xff000000;
        for (int c = 0; c < 3; c++) {
            // The lightmap is ARGB; material and tint are ABGR.
            int shift = (2 - c) * 8;
            float ambient = (map[0] >>> shift) & 255;
            float lit = sample(map, block, sky, shift);
            int floor = (int) Math.ceil((material >>> (8 * c) & 255) * Math.min(1f, ambient / Math.max(1f, lit)));
            result |= Math.max(tinted >>> (8 * c) & 255, Math.min(255, floor)) << (8 * c);
        }
        return result;
    }
    private static float sample(int[] map, float block, float sky, int shift) {
        float x = Math.max(0, Math.min(15, block - .5f)), y = Math.max(0, Math.min(15, sky - .5f));
        int ix = (int) x, iy = (int) y, jx = Math.min(15, ix + 1), jy = Math.min(15, iy + 1);
        float fx = x - ix, fy = y - iy;
        float a = (map[ix + iy * 16] >>> shift) & 255, b = (map[jx + iy * 16] >>> shift) & 255;
        float c = (map[ix + jy * 16] >>> shift) & 255, d = (map[jx + jy * 16] >>> shift) & 255;
        return (a + (b - a) * fx) * (1 - fy) + (c + (d - c) * fx) * fy;
    }

}
