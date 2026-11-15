/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Cable hue anchors follow GregTechCEu GTOverheatParticle black-body colors
 * at 52c04cd7bf03e5749a653580c12ef5856cc12e31 (LGPL-3.0).
 */
package com.sumirelabs.pulsar.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;

import com.sumirelabs.pulsar.rgb.RgbDefaultPalette;
import com.sumirelabs.pulsar.rgb.RgbLight;

import net.minecraft.block.state.IBlockState;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;

/** Owner-thread positional sampling only. No industrial mod classes are loaded. */
public final class IndustrialDynamicLightColors {

    private static final ClassValue<Optional<Field>> COLOUR = new ClassValue<>() {

        protected Optional<Field> computeValue(Class<?> type) {
            try {
                return Optional.of(type.getField("colour"));
            } catch (NoSuchFieldException absent) {
                return Optional.empty();
            }
        }

    };
    private static final ClassValue<Optional<Method>> DYE = method("getMetaValue");
    private static final ClassValue<Optional<Method>> TEMPERATURE = method("getTemperature");

    private IndustrialDynamicLightColors() {}

    private static ClassValue<Optional<Method>> method(String name) {
        return new ClassValue<>() {

            protected Optional<Method> computeValue(Class<?> type) {
                try {
                    return Optional.of(type.getMethod(name));
                } catch (NoSuchMethodException absent) {
                    return Optional.empty();
                }
            }

        };
    }

    public static int mekanismPanel(IBlockState state, IBlockAccess world, BlockPos pos) {
        int level = Math.max(0, Math.min(15, state.getLightValue(world, pos)));
        if (level == 0)
            return 0;
        Object tile = world.getTileEntity(pos);
        if (tile != null)
            try {
                var field = COLOUR.get(tile.getClass());
                Object color = field.isPresent() ? field.get().get(tile) : null;
                if (color != null) {
                    var getter = DYE.get(color.getClass());
                    Object value = getter.isPresent() ? getter.get().invoke(color) : null;
                    if (value instanceof Number number && number.intValue() >= 0 && number.intValue() < 16) {
                        // Mekanism mcMeta is dye damage, the reverse of block metadata.
                        int hue = RgbDefaultPalette.glassTransmittance(EnumDyeColor.byDyeDamage(number.intValue()));
                        return RgbLight.scale(RgbLight.normalize(hue), level);
                    }
                }
            } catch (ReflectiveOperationException unavailable) {
                /* Neutral fallback for replacements. */ }
        return level * 0x111;
    }

    public static int gregCable(IBlockState state, IBlockAccess world, BlockPos pos) {
        int level = Math.max(0, Math.min(15, state.getLightValue(world, pos)));
        if (level == 0)
            return 0;
        Object tile = world.getTileEntity(pos);
        if (tile != null)
            try {
                var getter = TEMPERATURE.get(tile.getClass());
                Object value = getter.isPresent() ? getter.get().invoke(tile) : null;
                if (value instanceof Number number)
                    return RgbLight.scale(cableHue(number.intValue()), level);
            } catch (ReflectiveOperationException unavailable) {
                /* Neutral fallback for replacements. */ }
        return level * 0x111;
    }

    static int cableHue(int kelvin) {
        // GTCEu begins emitting above 500 K and reaches full strength at 5000 K.
        // Interpolate hue independently; its contextual emission controls strength.
        int[] anchors = CABLE_HUES;
        int temperature = Math.max(1000, Math.min(5000, kelvin)) - 1000;
        int index = Math.min(3, temperature / 1000);
        int fraction = temperature - index * 1000;
        int packed = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            int a = anchors[index] >>> shift & 255, b = anchors[index + 1] >>> shift & 255;
            int channel = (a * (1000 - fraction) + b * fraction + 8500) / 17000;
            packed = packed << 4 | channel;
        }
        return packed;
    }
    private static final int[] CABLE_HUES = {0xFF3300, 0xFF8912, 0xFFB46B, 0xFFD1A3, 0xFFE4CE};

}
