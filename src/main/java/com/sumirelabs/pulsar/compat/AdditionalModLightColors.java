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
        hue(lookup, output, 0xFCA, "advancedrocketry:thermitetorch", "rustic:lantern_wood", "mia:torch_gold");
        hue(lookup, output, 0x611, "mia:redstone_lantern");
        hue(lookup, output, 0xF83, "endreborn:block_end_magma", "iceandfire:dragonforge_fire_core");
        hue(lookup, output, 0xC9F, "endreborn:block_purpur_lamp", "thaumadditions:taintkin_lit");
        hue(lookup, output, 0x8DF, "iceandfire:dread_torch", "iceandfire:dragonforge_ice_core",
            "integrateddynamics:menril_torch", "integrateddynamics:menril_torch_stone");
        hue(lookup, output, 0x8F8, "nuclearcraft:tritium_lamp");
        hue(lookup, output, 0xFC7, "thaumadditions:amber_lamp");
        hue(lookup, output, 0xBDF, "thaumicaugmentation:ancient_light");

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

    private static void hue(Function<ResourceLocation, Block> lookup, BiConsumer<IBlockState, Integer> output,
        int color, String... ids) {
        for (String id : ids)
            ModLightColors.register(lookup, output, id, state -> color);
    }

}
