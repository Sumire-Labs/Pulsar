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

    /** Convert SuperNova's 0x0R0G0B light format to Pulsar's 0xRGB.
     * Rejects nonzero padding bits; this does not quantize 24-bit display RGB.
     */
    public static int fromSuperNova(int color) {
        if ((color & ~0x0F0F0F) != 0)
            throw new IllegalArgumentException("SuperNova light color must be 0x0R0G0B");
        return (color >>> 8 & 0xF00) | (color >>> 4 & 0xF0) | (color & 15);
    }

    /** Convert Pulsar's 0xRGB to SuperNova's 0x0R0G0B light format. */
    public static int toSuperNova(int color) {
        checked(color);
        return (color & 0xF00) << 8 | (color & 0xF0) << 4 | color & 15;
    }
}
