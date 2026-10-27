/* SPDX-License-Identifier: LGPL-3.0-only
 * Hues follow SuperNova BotaniaColors, commit 22d8559c3064d77d115e58523c58cde824f4e214.
 * Names/properties verified against VazkiiMods/Botania 1.12-final, commit
 * 8a56a4a60c97be368b45c97da9d9da0ab6490962. No Botania classes are loaded.
 */
package com.sumirelabs.pulsar.compat;

import com.sumirelabs.pulsar.api.ColoredLightSource;
import com.sumirelabs.pulsar.rgb.RgbDefaultPalette;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.util.IStringSerializable;
import net.minecraft.util.ResourceLocation;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Conservative, optional 1.12.2 presets; keep contextual brightness and API overrides. */
public final class ModLightColors {
    private ModLightColors() {}

    public static void registerDefaults(BiConsumer<IBlockState, Integer> register) {
        registerDefaults(id -> Block.REGISTRY.containsKey(id) ? Block.REGISTRY.getObject(id) : null, register);
    }

    static void registerDefaults(Function<ResourceLocation, Block> lookup, BiConsumer<IBlockState, Integer> register) {
        register(lookup, register, "shinyFlower", state -> {
            for (var entry : state.getProperties().entrySet())
                if (entry.getKey().getName().equals("color") && entry.getValue() instanceof EnumDyeColor dye)
                    return RgbDefaultPalette.glassTransmittance(dye);
            return null; // Unknown state layout: leave the block unchanged.
        });
        register(lookup, register, "blazeBlock", state -> 0xFF0);
        register(lookup, register, "pylon", state -> {
            for (var entry : state.getProperties().entrySet()) {
                if (entry.getKey().getName().equals("variant") && entry.getValue() instanceof IStringSerializable variant)
                    return switch (variant.getName()) {
                        case "mana" -> 0x08F;
                        case "natura" -> 0x0F0;
                        case "gaia" -> 0xFAD;
                        default -> null;
                    };
            }
            return null;
        });
    }

    private static void register(Function<ResourceLocation, Block> lookup, BiConsumer<IBlockState, Integer> register,
                                 String name, Function<IBlockState, Integer> color) {
        Block block = lookup.apply(new ResourceLocation("botania", name));
        // An integration's own static/positional API always wins over our presets.
        if (block == null || block instanceof ColoredLightSource) return;
        for (IBlockState state : block.getBlockState().getValidStates()) {
            Integer value = color.apply(state);
            if (value != null) register.accept(state, value);
        }
    }
}
