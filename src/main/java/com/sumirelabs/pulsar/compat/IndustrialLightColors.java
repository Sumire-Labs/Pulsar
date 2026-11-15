/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Registry/property mappings verified from Mekanism 1.12 b032fb9,
 * GregTechCEu 52c04cd, EnderIO 1.12 9249b7e and Actually Additions 12206a0.
 * Palette choices are Pulsar hues; source strength remains mod-controlled.
 */

package com.sumirelabs.pulsar.compat;

import java.util.function.BiConsumer;
import java.util.function.Function;

import com.sumirelabs.pulsar.rgb.RgbDefaultPalette;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.util.ResourceLocation;

/** Optional industrial presets. Hues never create emission or override mod APIs. */
final class IndustrialLightColors {

    private IndustrialLightColors() {}

    static void registerDefaults(Function<ResourceLocation, Block> lookup, BiConsumer<IBlockState, Integer> output) {
        for (EnumDyeColor dye : EnumDyeColor.values()) {
            int hue = RgbDefaultPalette.glassTransmittance(dye);
            hue(lookup, output, hue, "gregtech:" + dye.getName() + "_lamp",
                "gregtech:borderless_" + dye.getName() + "_lamp");
        }
        ModLightColors.register(lookup, output, "mekanism:glowplasticblock", IndustrialLightColors::dyeHue);
        ModLightColors.register(lookup, output, "mekanism:basicblock",
            state -> switch (String.valueOf(ModLightColors.namedProperty(state, "type"))) {
                case "refined_obsidian" -> 0xB8F;
                case "refined_glowstone" -> 0xFD3;
                case "teleporter_frame" -> 0x9BE;
                default -> null;
            });
        ModLightColors.register(lookup, output, "mekanism:basicblock2",
            state -> "superheating_element".equals(ModLightColors.namedProperty(state, "type")) ? 0xFCA : null);
        ModLightColors.register(lookup, output, "mekanism:machineblock",
            state -> "energized_smelter".equals(ModLightColors.namedProperty(state, "type")) ? 0xDCA : null);
        ModLightColors.register(lookup, output, "mekanismgenerators:generator",
            state -> switch (String.valueOf(ModLightColors.namedProperty(state, "type"))) {
                case "heat_generator" -> 0xFCA;
                case "gas_generator" -> 0x9DF;
                case "bio_generator" -> 0xBE8;
                default -> null;
            });
        hue(lookup, output, 0xFED, "enderio:block_electric_light", "enderio:block_light_node");
        hue(lookup, output, 0x9DF, "enderio:block_cold_fire");
        hue(lookup, output, 0xFD3, "enderio:block_painted_glowstone", "enderio:block_painted_glowstone_solid");
        hue(lookup, output, 0xFE8, "enderio:block_fluid_liquid_sunshine");
        for (String id : new String[]{"block_enlightened_fused_quartz",
            "block_enlightened_fused_glass",
            "block_holy_enlightened_fused_quartz",
            "block_holy_enlightened_fused_glass",
            "block_unholy_enlightened_fused_quartz",
            "block_unholy_enlightened_fused_glass",
            "block_pasture_enlightened_fused_quartz",
            "block_pasture_enlightened_fused_glass",
            "block_not_holy_enlightened_fused_quartz",
            "block_not_holy_enlightened_fused_glass",
            "block_not_unholy_enlightened_fused_quartz",
            "block_not_unholy_enlightened_fused_glass",
            "block_not_pasture_enlightened_fused_quartz",
            "block_not_pasture_enlightened_fused_glass"})
            ModLightColors.register(lookup, output, "enderio:" + id, IndustrialLightColors::dyeHue);
        for (String id : new String[]{"block_colored_lamp", "block_colored_lamp_on"})
            ModLightColors.register(lookup, output, "actuallyadditions:" + id, state -> {
                String name = ModLightColors.namedProperty(state, "type");
                for (EnumDyeColor dye : EnumDyeColor.values())
                    if (dye.getName().equals(name) || dye == EnumDyeColor.SILVER && "light_gray".equals(name))
                        return RgbDefaultPalette.glassTransmittance(dye);
                return null;
            });
        hue(lookup, output, 0xDA8, "actuallyadditions:block_tiny_torch");
        hue(lookup, output, 0xDCA, "actuallyadditions:block_furnace_double");
    }

    private static Integer dyeHue(IBlockState state) {
        EnumDyeColor dye = ModLightColors.dye(state);
        return dye == null ? null : RgbDefaultPalette.glassTransmittance(dye);
    }

    private static void hue(Function<ResourceLocation, Block> lookup, BiConsumer<IBlockState, Integer> output,
        int color, String... names) {
        for (String name : names)
            ModLightColors.register(lookup, output, name, state -> color);
    }

}
