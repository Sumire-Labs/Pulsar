/* SPDX-License-Identifier: LGPL-3.0-only */
package com.sumirelabs.pulsar.compat;

import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.sumirelabs.pulsar.api.ColoredLightSource;
import com.sumirelabs.pulsar.rgb.RgbDefaultPalette;
import com.sumirelabs.pulsar.rgb.RgbLight;
import com.sumirelabs.pulsar.rgb.RgbLightAccess;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.properties.PropertyInteger;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Bootstrap;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.util.IStringSerializable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;

import static org.junit.jupiter.api.Assertions.*;

class AdditionalModLightColorsTest {

    @BeforeAll
    static void bootstrap() {
        Bootstrap.register();
    }

    private static class Plain extends Block {

        Plain() {
            super(Material.ROCK);
        }

    }
    private static Map<IBlockState, Integer> colors(Map<String, Block> blocks) {
        Map<IBlockState, Integer> result = new IdentityHashMap<>();
        ModLightColors.registerDefaults(id -> blocks.get(id.toString()), result::put);
        return result;
    }
    private static final PropertyBool ON = PropertyBool.create("on");
    private static final PropertyInteger LEVEL = PropertyInteger.create("level", 0, 15);
    private static class ContextualLamp extends Plain {

        @Override
        protected BlockStateContainer createBlockState() {
            return new BlockStateContainer(this, ON, LEVEL);
        }
        @Override
        public int getLightValue(IBlockState state, IBlockAccess world, BlockPos pos) {
            return state.getValue(ON) ? state.getValue(LEVEL) : 0;
        }

    }
    @Test
    void nativeOnOffAndEveryContextualLevelRemainAuthoritative() {
        for (String id : new String[]{"simplylight:illuminant_block", "simplylight:illuminant_block_on",
            "mia:redstone_lantern", "xtones:lamp_flat", "iceandfire:dragonforge_fire_core"}) {
            Block lamp = new ContextualLamp();
            var registered = colors(Map.of(id, lamp));
            assertEquals(32, registered.size(), id);
            for (var state : lamp.getBlockState().getValidStates()) {
                int scalar = state.getLightValue(null, BlockPos.ORIGIN);
                for (boolean projected : new boolean[]{false, true}) {
                    int emitted = RgbLightAccess.effectiveEmission(RgbLight.normalize(registered.get(state)), scalar,
                        projected);
                    assertEquals(scalar, Math.max(emitted >> 8, Math.max(emitted >> 4 & 15, emitted & 15)), id);
                }
            }
        }
    }

    enum Stone implements IStringSerializable {

        CARVED_STONE, SENTRY_STONE, ANGELIC_STONE, LIGHT_ANGELIC_STONE, HELLFIRE_STONE, LIGHT_HELLFIRE_STONE;
        public String getName() {
            return name().toLowerCase(Locale.ROOT);
        }

    }
    private static final PropertyEnum<Stone> STONE = PropertyEnum.create("aether_legacy_dungeon_stone", Stone.class);
    private static class DungeonStone extends Plain {

        @Override
        protected BlockStateContainer createBlockState() {
            return new BlockStateContainer(this, STONE);
        }

    }
    @Test
    void aetherUsesSerializedVariantsInsteadOfInventingModelRegistryIds() {
        Block stone = new DungeonStone();
        var registered = colors(Map.of("aether_legacy:dungeon_block", stone));
        assertEquals(3, registered.size());
        assertEquals(0xFC7, registered.get(stone.getDefaultState().withProperty(STONE, Stone.SENTRY_STONE)));
        assertEquals(0xFED, registered.get(stone.getDefaultState().withProperty(STONE, Stone.LIGHT_ANGELIC_STONE)));
        assertEquals(0xFA6, registered.get(stone.getDefaultState().withProperty(STONE, Stone.LIGHT_HELLFIRE_STONE)));
        assertFalse(registered.containsKey(stone.getDefaultState().withProperty(STONE, Stone.ANGELIC_STONE)));
        assertTrue(colors(Map.of("aether_legacy:dungeon_block", new Plain())).isEmpty());
        assertTrue(colors(Map.of("aether_legacy:light_angelic_stone", new Plain(),
            "simplylight:lampblock", new Plain(), "simplylight:floorlamp", new Plain())).isEmpty());
    }

    private static final PropertyEnum<EnumDyeColor> COLOR = PropertyEnum.create("color", EnumDyeColor.class);
    private static class RainbowLamp extends Plain {

        @Override
        protected BlockStateContainer createBlockState() {
            return new BlockStateContainer(this, COLOR);
        }

    }
    @Test
    void rainbowLampUsesItsNativeDyeStateAndRejectsUnrecognizedLayouts() {
        Block lamp = new RainbowLamp().setLightLevel(1);
        var registered = colors(Map.of("randomthings:rainbowlamp", lamp));
        assertEquals(16, registered.size());
        for (EnumDyeColor dye : EnumDyeColor.values())
            assertEquals(RgbDefaultPalette.glassTransmittance(dye),
                registered.get(lamp.getDefaultState().withProperty(COLOR, dye)));
        assertTrue(colors(Map.of("randomthings:rainbowlamp", new Plain())).isEmpty());
    }

    enum LampType implements IStringSerializable {

        YELLOW, WHITE, YELLOW_LANTERN, WHITE_LANTERN, UNKNOWN;
        public String getName() {
            return name().toLowerCase(Locale.ROOT);
        }

    }
    private static final PropertyEnum<LampType> LAMP_TYPE = PropertyEnum.create("lamp_type", LampType.class);
    private static class TechgunsLamp extends Plain {

        @Override
        protected BlockStateContainer createBlockState() {
            return new BlockStateContainer(this, LAMP_TYPE);
        }

    }
    @Test
    void techgunsLampAndLanternVariantsUseRegistryIdAndLeaveUnknownTypesAlone() {
        Block lamp = new TechgunsLamp().setLightLevel(1);
        var registered = colors(Map.of("techguns:lamp0", lamp));
        assertEquals(4, registered.size());
        assertEquals(0xFCA, registered.get(lamp.getDefaultState().withProperty(LAMP_TYPE, LampType.YELLOW_LANTERN)));
        assertEquals(0xFFF, registered.get(lamp.getDefaultState().withProperty(LAMP_TYPE, LampType.WHITE)));
        assertFalse(registered.containsKey(lamp.getDefaultState().withProperty(LAMP_TYPE, LampType.UNKNOWN)));
        assertTrue(colors(Map.of("techguns:lamp0", new Plain(), "techguns:lamp_white", new Plain())).isEmpty());
    }

    private static class CustomSource extends Plain implements ColoredLightSource {

        public int getColoredLightEmission(IBlockState state) {
            return 0x246;
        }

    }
    @Test
    void absentModsDarkBlocksAndCustomColorApisAreNotTurnedIntoNewSources() {
        assertTrue(colors(Map.of()).isEmpty());
        assertTrue(colors(Map.of("nb:soul_torch", new CustomSource())).isEmpty());
        for (String id : new String[]{"randomthings:lapislamp", "theaurorian:moontorch", "journey:bloodcatalyst"}) {
            Block dark = new Plain();
            int hue = colors(Map.of(id, dark)).get(dark.getDefaultState());
            for (boolean projected : new boolean[]{false, true})
                assertEquals(0, RgbLightAccess.effectiveEmission(RgbLight.normalize(hue), 0, projected));
        }
    }
    @Test
    void existingDefaultsKeepTheirColorsAndSoulLightsKeepTheirNativeStrength() {
        Block oldTorch = new Plain().setLightLevel(.9375f);
        Block soul = new Plain().setLightLevel(.7f);
        var registered = colors(Map.of("actuallyadditions:block_tiny_torch", oldTorch, "nb:soul_torch", soul));
        assertEquals(0xDA8, registered.get(oldTorch.getDefaultState()));
        int emitted = RgbLightAccess.effectiveEmission(RgbLight.normalize(registered.get(soul.getDefaultState())),
            soul.getDefaultState().getLightValue(), true);
        assertEquals(10, Math.max(emitted >> 8, Math.max(emitted >> 4 & 15, emitted & 15)));
    }

}
