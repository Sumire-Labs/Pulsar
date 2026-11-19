/* SPDX-License-Identifier: LGPL-3.0-only
 * Registry/state names checked against 1.12.2 mod sources and release assets.
 * Presets define chromaticity while preserving native emission.
 */
package com.sumirelabs.pulsar.compat;

import java.util.function.BiConsumer;
import java.util.function.Function;

import com.sumirelabs.pulsar.rgb.RgbDefaultPalette;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.util.ResourceLocation;

/** Additional optional mod source hues; never creates emission or changes native on/off logic. */
final class AdditionalModLightColors {

    private AdditionalModLightColors() {}

    static void registerDefaults(Function<ResourceLocation, Block> lookup, BiConsumer<IBlockState, Integer> output) {
        registerAuditedDefaults(lookup, output);
        hue(lookup, output, 0xFCA, "advancedrocketry:thermitetorch", "rustic:lantern_wood", "mia:torch_gold");
        hue(lookup, output, 0x611, "mia:redstone_lantern");
        hue(lookup, output, 0xF83, "endreborn:block_end_magma", "iceandfire:dragonforge_fire_core");
        hue(lookup, output, 0xC9F, "endreborn:block_purpur_lamp", "thaumadditions:taintkin_lit");
        hue(lookup, output, 0x8DF, "iceandfire:dread_torch", "iceandfire:dragonforge_ice_core",
            "integrateddynamics:menril_torch", "integrateddynamics:menril_torch_stone");
        hue(lookup, output, 0x8F8, "nuclearcraft:tritium_lamp");
        hue(lookup, output, 0xFC7, "thaumadditions:amber_lamp");
        // This release registers one stone block; ancient_light is a model/state name.
        ModLightColors.register(lookup, output, "thaumicaugmentation:stone", state -> switch (String.valueOf(
            ModLightColors.namedProperty(state, "ta_stone_type"))) {
            case "ancient_light" -> 0xBDF;
            case "stone_crusted_glowing" -> 0xC9F;
            default -> null;
        });

        ModLightColors.register(lookup, output, "techguns:lamp0", state -> switch (String.valueOf(
            ModLightColors.namedProperty(state, "lamp_type"))) {
            case "yellow", "yellow_lantern" -> 0xFCA;
            case "white", "white_lantern" -> 0xFFF;
            default -> null;
        });

        // Random Things' lapis lamp deliberately emits only on the client. Its native
        // contextual light value still decides whether it illuminates a server world.
        hue(lookup, output, 0x58F, "randomthings:lapislamp");
        hue(lookup, output, 0xFFF, "randomthings:quartzlamp");
        ModLightColors.register(lookup, output, "randomthings:rainbowlamp", state -> {
            EnumDyeColor dye = ModLightColors.dye(state);
            return dye == null ? null : RgbDefaultPalette.glassTransmittance(dye);
        });

        // Model names such as lampblock/floorlamp are not Simply Light registry IDs.
        // Inverted lamps keep their own contextual redstone/on property behavior.
        hue(lookup, output, 0xFFF, "simplylight:illuminant_block", "simplylight:illuminant_block_on",
            "simplylight:illuminant_slab", "simplylight:illuminant_panel", "simplylight:edge_light",
            "simplylight:rodlamp", "simplylight:lightbulb", "simplylight:wall_lamp");

        hue(lookup, output, 0x8DF, "theaurorian:moontorch", "theaurorian:moontemplelamp",
            "theaurorian:mushroomcrystal");
        hue(lookup, output, 0xDA8, "theaurorian:silentwoodtorch");
        hue(lookup, output, 0xA9F, "theaurorian:runestonelamp", "theaurorian:darkstonelamp");

        hue(lookup, output, 0xFE8, "aether_legacy:ambrosium_torch");
        for (String id : new String[]{"aether_legacy:dungeon_block", "aether_legacy:locked_dungeon_block"})
            ModLightColors.register(lookup, output, id, state -> switch (String.valueOf(
                ModLightColors.namedProperty(state, "aether_legacy_dungeon_stone"))) {
                case "sentry_stone" -> 0xFC7;
                case "light_angelic_stone" -> 0xFED;
                case "light_hellfire_stone" -> 0xFA6;
                default -> null;
            });
        hue(lookup, output, 0xBDF, "lost_aether:light_gale_stone", "lost_aether:locked_light_gale_stone");
        hue(lookup, output, 0xFCA, "betternether:cincinnasite_lantern");
        hue(lookup, output, 0xFFF, "xtones:lamp_flat");

        // Journey normalizes its legacy camel-case registry names to lower case.
        hue(lookup, output, 0xF88, "journey:bloodlamp", "journey:bloodcatalyst");
        hue(lookup, output, 0xF83, "journey:boilinglamp", "journey:nethiclamp");
        hue(lookup, output, 0x8F8, "journey:corbalamp");
        hue(lookup, output, 0xFFF, "journey:cloudialamp");
        hue(lookup, output, 0x8DF, "journey:frozenlamp", "journey:depths_lamp", "journey:depthslight");
        hue(lookup, output, 0xC9F, "journey:terranialamp");
        hue(lookup, output, 0xDA8, "journey:dungeonlamp", "journey:dungeonlampstairs", "journey:dungeonlampfence");
        hue(lookup, output, 0x8FA, "journey:euca_runic_bricks", "journey:euca_square_runic_bricks",
            "journey:euca_runic_lamp");
        hue(lookup, output, 0x9DF, "journey:ancient_stone_runic_0", "journey:ancient_stone_runic_1",
            "journey:ancient_stone_runic_2", "journey:ancient_stone_runic_3", "journey:obelisk");

        // Unseen's Nether Backport retains its native level-10 soul lights.
        hue(lookup, output, 0x8DF, "nb:soul_torch", "nb:soul_lantern", "nb:soul_fire");
        hue(lookup, output, 0xFC7, "nb:shroom_light");
        hue(lookup, output, 0xFCA, "da:fire_stone_lit", "da:aza_vines_berries");
        hue(lookup, output, 0xBDF, "da:farum_lantern");
    }

    /** Additional native emitters found by auditing 1.12.2 registrations and emission implementations. */
    private static void registerAuditedDefaults(Function<ResourceLocation, Block> lookup,
        BiConsumer<IBlockState, Integer> output) {
        hue(lookup, output, 0xF44, "actuallyadditions:block_crystal_cluster_redstone");
        hue(lookup, output, 0x48F, "actuallyadditions:block_crystal_cluster_lapis");
        hue(lookup, output, 0x6DF, "actuallyadditions:block_crystal_cluster_diamond");
        hue(lookup, output, 0x6F8, "actuallyadditions:block_crystal_cluster_emerald");
        hue(lookup, output, 0xFFF, "actuallyadditions:block_crystal_cluster_iron",
            "actuallyadditions:block_crystal_cluster_coal");
        hue(lookup, output, 0xFCA, "actuallyadditions:block_grinder", "actuallyadditions:block_grinder_double");
        hue(lookup, output, 0x8FA, "actuallyadditions:block_item_repairer");
        hue(lookup, output, 0x8DF, "biomesoplenty:blue_fire", "iceandfire:dread_portal");
        hue(lookup, output, 0xFC7, "iceandfire:myrmex_desert_biolight");
        hue(lookup, output, 0x8FA, "iceandfire:myrmex_jungle_biolight");

        ModLightColors.register(lookup, output, "botania:dreamwood",
            state -> "glimmering".equals(ModLightColors.namedProperty(state, "variant")) ? 0x08F : null);
        hue(lookup, output, 0x611, "botania:animatedTorch");
        hue(lookup, output, 0xF83, "tconstruct:firewood", "tconstruct:firewood_slab",
            "tconstruct:firewood_stairs", "tconstruct:lavawood_stairs");

        hue(lookup, output, 0xFCA, "rustic:candle", "rustic:candle_double", "rustic:candle_lever",
            "rustic:candle_gold", "rustic:candle_double_gold", "rustic:candle_lever_gold",
            "rustic:candle_silver", "rustic:candle_double_silver", "rustic:candle_lever_silver",
            "rustic:iron_lantern", "rustic:golden_lantern", "rustic:silver_lantern");
        for (String id : new String[]{"randomthings:luminousblock", "randomthings:luminousstainedbrick",
            "randomthings:translucentluminousblock"})
            ModLightColors.register(lookup, output, id, state -> {
                EnumDyeColor dye = ModLightColors.dye(state);
                return dye == null ? null : RgbDefaultPalette.glassTransmittance(dye);
            });
        hue(lookup, output, 0xF83, "randomthings:blazingfire");

        String[] panes = {"white", "red", "orange", "yellow", "green", "blue", "indigo", "violet", "black"};
        int[] hues = {0xFFF, 0xF00, 0xF80, 0xFF0, 0x0F0, 0x0FF, 0x00F, 0xF0F, 0xFFF};
        for (int i = 0; i < panes.length; i++)
            hue(lookup, output, hues[i], "quark:crystal_" + panes[i] + "_pane");
        hue(lookup, output, 0xFCA, "quark:blaze_rod");
        hue(lookup, output, 0xFD3, "quark:glowstone_dust_block");
        hue(lookup, output, 0xF83, "quark:magma_bricks", "quark:magma_bricks_slab",
            "quark:magma_bricks_slab_double", "quark:magma_bricks_stairs");

        hue(lookup, output, 0xFCA, "thaumcraft:cinderpearl", "thaumcraft:lamp_arcane",
            "thaumcraft:lamp_fertility", "thaumcraft:lamp_growth");
        hue(lookup, output, 0xBDF, "thaumcraft:shimmerleaf", "thaumcraft:log_silverwood");
        hue(lookup, output, 0xC9F, "thaumcraft:vishroom", "thaumcraft:taint_fibre", "thaumcraft:vis_battery");
        hue(lookup, output, 0xF83, "thaumcraft:infernal_furnace", "thaumcraft:smelter_basic",
            "thaumcraft:smelter_thaumium", "thaumcraft:smelter_void", "thaumadditions:adaminite_smelter",
            "thaumadditions:mithminite_smelter", "thaumadditions:mithrillium_smelter");
        hue(lookup, output, 0xC9F, "thaumicaugmentation:taint_flower", "thaumicaugmentation:strange_crystal",
            "thaumicaugmentation:rift_barrier");
        hue(lookup, output, 0xBDF, "thaumicaugmentation:obelisk");
        hue(lookup, output, 0x8F8, "twilightforest:moonworm");
        hue(lookup, output, 0xFE8, "aether_legacy:quicksoil_glass");
        hue(lookup, output, 0xFCA, "betternether:cincinnasite_fire_bowl", "betternether:cincinnasite_forge");
        hue(lookup, output, 0xCF7, "betternether:lucis_mushroom", "betternether:lucis_spore");
        hue(lookup, output, 0xC9F, "nb:cry_obi", "nb:respawn_anchor");
        hue(lookup, output, 0x8DF, "theaurorian:crystal", "theaurorian:mysticalbarrier",
            "theaurorian:aurorianportal");
        hue(lookup, output, 0xF83, "theaurorian:aurorianfurnacelit");
        hue(lookup, output, 0xBDF, "da:cold_brick_lamp", "da:frost_icicle");
        hue(lookup, output, 0x9DF, "journey:ancient_obelisk");
        hue(lookup, output, 0x8F8, "journey:bog_shrooms_small", "journey:bog_shroom_tall");
        hue(lookup, output, 0xC9F, "journey:enchanted_shrooms_small", "journey:enchanted_shroom_tall",
            "journey:tallterramushroom");
        hue(lookup, output, 0xFFF, "journey:cloudiabrick", "journey:cloudiawall", "journey:cloudiatile",
            "journey:cloudialeaves");
        // Only fluids with native luminosity receive a source preset. Potion colors
        // and tank contents are contextual and cannot be represented by one hue.
        hue(lookup, output, 0xF22, "thermalfoundation:fluid_redstone");
        hue(lookup, output, 0x4FB, "thermalfoundation:fluid_ender");
        hue(lookup, output, 0xF83, "thermalfoundation:fluid_pyrotheum");
        hue(lookup, output, 0x48F, "thermalfoundation:fluid_mana");
        hue(lookup, output, 0x9F4, "taiga:tiberium_block");
        hue(lookup, output, 0xDBF, "taiga:prometheum_block");
        hue(lookup, output, 0xF46, "taiga:seismodium_block");
        hue(lookup, output, 0xBDF, "taiga:bysmuid_block");

    }

    private static void hue(Function<ResourceLocation, Block> lookup, BiConsumer<IBlockState, Integer> output,
        int color, String... ids) {
        for (String id : ids)
            ModLightColors.register(lookup, output, id, state -> color);
    }

}
