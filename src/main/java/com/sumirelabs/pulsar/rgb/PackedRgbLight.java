/* SPDX-License-Identifier: LGPL-3.0-only
 * Adapted from GTNewHorizons/SuperNova PackedColorLight and PackedColorLightQueue.
 * Reference: 22d8559c3064d77d115e58523c58cde824f4e214
 * https://github.com/GTNewHorizons/SuperNova
 * Internal propagation format 0x0R0G0B; public Pulsar API remains 0xRGB.
 */
package com.sumirelabs.pulsar.rgb;

/**
 * Utilities for packed RGB light values. Each channel is 0-15. Use {@link #pack(int, int, int)} to create and {@link #red}/{@link #green}/{@link #blue} to
 * extract.
 */
public final class PackedRgbLight {

    /** Bit offset of each channel within the packed word. */
    public static final int BLUE_SHIFT = 0;
    public static final int GREEN_SHIFT = 8;
    public static final int RED_SHIFT = 16;

    /** Mask for a single 4-bit channel value (post-shift). */
    public static final int CHANNEL_MASK = 0xF;

    /** In-place masks for each channel. */
    public static final int BLUE_MASK = CHANNEL_MASK << BLUE_SHIFT;   // 0x00000F
    public static final int GREEN_MASK = CHANNEL_MASK << GREEN_SHIFT; // 0x000F00
    public static final int RED_MASK = CHANNEL_MASK << RED_SHIFT;     // 0x0F0000

    /** Union of all channel bits: {@code 0x0F0F0F}. */
    public static final int ALL_CHANNELS = RED_MASK | GREEN_MASK | BLUE_MASK;

    // Internal: borrow barriers between channels for SWAR arithmetic. Not part of the public API contract.
    public static final int SPACER_BITS = 0x101010;

    private PackedRgbLight() {}

    /**
     * Pack three light levels into an int. Values are masked to 0-15.
     *
     * @param r red channel (0-15)
     * @param g green channel (0-15)
     * @param b blue channel (0-15)
     * @return packed RGB value
     */
    public static int pack(final int r, final int g, final int b) {
        return ((r & CHANNEL_MASK) << RED_SHIFT) | ((g & CHANNEL_MASK) << GREEN_SHIFT) | ((b & CHANNEL_MASK) << BLUE_SHIFT);
    }

    /** Extract the red channel (0-15) from a packed value. */
    public static int red(final int packed) {
        return (packed >>> RED_SHIFT) & CHANNEL_MASK;
    }

    /** Extract the green channel (0-15) from a packed value. */
    public static int green(final int packed) {
        return (packed >>> GREEN_SHIFT) & CHANNEL_MASK;
    }

    /** Extract the blue channel (0-15) from a packed value. */
    public static int blue(final int packed) {
        return (packed >>> BLUE_SHIFT) & CHANNEL_MASK;
    }

    /**
     * Subtract opacity from each channel, clamping to 0: {@code max(channel - opacity, 0)}.
     *
     * @param packed  packed RGB
     * @param opacity value to subtract from each channel (0-15)
     * @return packed RGB result
     */
    public static int packedSub(final int packed, final int opacity) {
        return packedSubRGB(packed, pack(opacity, opacity, opacity));
    }

    /** Convert packed transmittance (15=transparent) to packed absorption (15=opaque). */
    public static int transmittanceToAbsorption(final int transmittance) {
        return pack(15 - red(transmittance), 15 - green(transmittance), 15 - blue(transmittance));
    }

    public static int packedSubRGB(final int packed, final int absorption) {
        final int diff = (packed | SPACER_BITS) - (absorption & ALL_CHANNELS);
        final int underflowMask = ~diff & SPACER_BITS;
        final int clearMask = underflowMask - (underflowMask >>> 4);
        return (diff & ~clearMask) & ALL_CHANNELS;
    }

    /**
     * Component-wise max of two packed RGB values.
     *
     * @param a first packed RGB
     * @param b second packed RGB
     * @return packed RGB where each channel is {@code max(a_ch, b_ch)}
     */
    public static int packedMax(final int a, final int b) {
        // Spacer bit survives subtraction iff a >= b for that channel
        final int diff = (a | SPACER_BITS) - (b & ALL_CHANNELS);
        final int aGeB = diff & SPACER_BITS;
        // Spread comparison bits to full channel select masks
        final int selectA = (aGeB >>> 4) * 0xF;
        return ((a & selectA) | (b & ~selectA)) & ALL_CHANNELS;
    }

    /**
     * Returns {@code true} if any channel of {@code a} is strictly greater than the corresponding channel of {@code b}.
     *
     * @param a first packed RGB
     * @param b second packed RGB
     */
    public static boolean anyComponentGreater(final int a, final int b) {
        // If any spacer bit clears after (b|spacers)-a, then b < a for that channel
        final int diff = (b | SPACER_BITS) - (a & ALL_CHANNELS);
        return (diff & SPACER_BITS) != SPACER_BITS;
    }

    /**
     * Returns the brightest channel as a scalar 0-15 value.
     *
     * @param packed packed RGB
     * @return {@code max(r, g, b)}
     */
    public static int maxComponent(final int packed) {
        return Math.max(red(packed), Math.max(green(packed), blue(packed)));
    }

    /**
     * Returns {@code true} if any channel is non-zero.
     *
     * @param packed packed RGB
     */
    public static boolean anyNonZero(final int packed) {
        return (packed & ALL_CHANNELS) != 0;
    }

    /**
     * Returns a mask with 0xF in each channel position that is non-zero, 0x0 where zero. Used to detect which channels are "present" in a packed value.
     * <p>
     * Example: {@code channelPresenceMask(pack(5, 0, 3))} returns {@code 0x0F000F} (R and B present).
     */
    public static int channelPresenceMask(final int packed) {
        // Fold all bits within each 4-bit channel down to bit 0, then spread to full mask
        int x = packed;
        x |= x >>> 2;
        x |= x >>> 1;
        x &= 0x010101; // isolate bit 0 of each channel
        return x * 0x0F; // spread to 0xF per channel
    }
    public static int fromCompact(int rgb) {
        return (rgb & 0xF00) << 8 | (rgb & 0x0F0) << 4 | rgb & 0x00F;
    }
    public static int toCompact(int rgb) {
        return (rgb >>> 8 & 0xF00) | (rgb >>> 4 & 0x0F0) | rgb & 0x00F;
    }
    public static long encodeQueueLevel(int rgb) {
        return ((long)(rgb & 0x0F0000) << 12) | ((long)(rgb & 0x000F00) << 24)
                | ((long)(rgb & 0x00000F) << 36);
    }
    public static int decodeQueueLevel(long entry) {
        return (int)(entry >>> 12 & 0x0F0000) | (int)(entry >>> 24 & 0x000F00)
                | (int)(entry >>> 36 & 0x00000F);
    }
    /** Full component masks where a is strictly greater than b. */
    public static int maskWhereGreater(int a, int b) {
        int diff = (b | SPACER_BITS) - (a & ALL_CHANNELS);
        return ((~diff & SPACER_BITS) >>> 4) * 15;
    }
}
