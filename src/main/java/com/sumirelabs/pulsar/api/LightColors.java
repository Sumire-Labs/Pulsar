package com.sumirelabs.pulsar.api;

/** Basic additive light colors in Pulsar's 0xRGB format, not Minecraft dye colors.
 * These constants describe component strengths; registry hue normalization and
 * the selected gameplay-light policy still apply. No gamma conversion is implied.
 */
public final class LightColors {
    public static final int BLACK = 0x000;
    public static final int WHITE = 0xFFF;
    public static final int RED = 0xF00;
    public static final int GREEN = 0x0F0;
    public static final int BLUE = 0x00F;
    public static final int YELLOW = 0xFF0;
    public static final int CYAN = 0x0FF;
    public static final int MAGENTA = 0xF0F;

    private LightColors() {}
}
