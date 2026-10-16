package com.sumirelabs.pulsar.rgb;

/** Four bits per component, 0xRGB. Rendering color, never a gameplay light level. */
public final class RgbLight {
    private RgbLight() {}
    public static int pack(int r, int g, int b) {
        if ((r | g | b) < 0 || r > 15 || g > 15 || b > 15) throw new IllegalArgumentException("RGB components must be 0..15");
        return r << 8 | g << 4 | b;
    }
    public static int channel(int rgb, int channel) { return rgb >> ((2 - channel) * 4) & 15; }
    public static int normalize(int rgb) {
        int max = Math.max(channel(rgb, 0), Math.max(channel(rgb, 1), channel(rgb, 2)));
        if (max == 0) throw new IllegalArgumentException("A light chromaticity cannot be black");
        return pack((channel(rgb, 0) * 15 + max / 2) / max,
                (channel(rgb, 1) * 15 + max / 2) / max, (channel(rgb, 2) * 15 + max / 2) / max);
    }
    public static int scale(int rgb, int level) {
        return pack((channel(rgb, 0) * level + 7) / 15,
                (channel(rgb, 1) * level + 7) / 15, (channel(rgb, 2) * level + 7) / 15);
    }
}
