package com.sumirelabs.pulsar.api;

/**
 * Public RGB value format: {@code 0xRGB}, with each component in 0..15.
 * This is neither a vanilla packed lightmap coordinate nor a 24-bit display color.
 * Values describe emission or transmittance according to the receiving API.
 */
public final class PackedLightColor {
    public static final int BLACK = 0x000;
    public static final int WHITE = 0xFFF;

    private PackedLightColor() {}

    /** Pack three light components, rejecting values outside 0..15. */
    public static int pack(int red, int green, int blue) {
        return com.sumirelabs.pulsar.rgb.RgbLight.pack(red, green, blue);
    }

    /** Validate a packed value and return it unchanged. */
    public static int checked(int color) {
        if ((color & ~0xFFF) != 0)
            throw new IllegalArgumentException("Light color must be 0xRGB with four bits per component");
        return color;
    }

    public static int red(int color) { return checked(color) >>> 8; }
    public static int green(int color) { return checked(color) >>> 4 & 15; }
    public static int blue(int color) { return checked(color) & 15; }

    /** Convert transmittance to absorption (or absorption to transmittance). */
    public static int complement(int color) { return checked(color) ^ WHITE; }
}
