/* SPDX-License-Identifier: LGPL-3.0-only
 * Stained-glass transmittance adapted from GTNewHorizons/SuperNova
 * LightColors.BRIGHT_DYE_PALETTE and DefaultTranslucency, commit
 * 22d8559c3064d77d115e58523c58cde824f4e214 (https://github.com/GTNewHorizons/SuperNova).
 * Pulsar uses 1.12.2 dye names and compact 0xRGB values.
 */
package com.sumirelabs.pulsar.rgb;

import net.minecraft.item.EnumDyeColor;

/** Optical defaults: texture/dye albedo is not light transmittance. */
public final class RgbDefaultPalette {
    private RgbDefaultPalette() {}

    public static int glassTransmittance(EnumDyeColor dye) {
        return switch (dye) {
            case WHITE -> 0xFFF;
            case ORANGE -> 0xFCA;
            case MAGENTA -> 0xF0F;
            case LIGHT_BLUE -> 0x08F;
            case YELLOW -> 0xFF0;
            case LIME -> 0x8F0;
            case PINK -> 0xF7A;
            case GRAY -> 0x555;
            case SILVER -> 0xAAA;
            case CYAN -> 0x0FF;
            case PURPLE -> 0xA0F;
            case BLUE -> 0x00F;
            case BROWN -> 0x830;
            case GREEN -> 0x0F0;
            case RED -> 0xF00;
            case BLACK -> 0x111;
        };
    }
}
