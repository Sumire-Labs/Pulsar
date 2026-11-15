/*
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.sumirelabs.pulsar.compat;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.sumirelabs.pulsar.api.PositionalColoredLightSource;
import com.sumirelabs.pulsar.rgb.RgbDefaultPalette;
import com.sumirelabs.pulsar.rgb.RgbLight;
import com.sumirelabs.pulsar.rgb.RgbLightAccess;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Bootstrap;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;

import static org.junit.jupiter.api.Assertions.*;

class IndustrialLightColorsTest {

    @BeforeAll
    static void bootstrap() {
        Bootstrap.register();
    }

    static final PropertyBool LIGHT = PropertyBool.create("light"), POWERED = PropertyBool.create("powered"),
        INVERTED = PropertyBool.create("inverted"), BLOOM = PropertyBool.create("bloom");
    static class GregLamp extends Block {

        GregLamp() {
            super(Material.REDSTONE_LIGHT);
        }
        protected BlockStateContainer createBlockState() {
            return new BlockStateContainer(this, LIGHT, POWERED, INVERTED, BLOOM);
        }
        public int getLightValue(IBlockState state) {
            return state.getValue(LIGHT) && state.getValue(POWERED) == state.getValue(INVERTED) ? 15 : 0;
        }

    }
    static final PropertyEnum<EnumDyeColor> COLOR = PropertyEnum.create("color", EnumDyeColor.class);
    static class Dyed extends Block {

        Dyed() {
            super(Material.GLASS);
        }
        protected BlockStateContainer createBlockState() {
            return new BlockStateContainer(this, COLOR);
        }

    }
    static class ApiLamp extends GregLamp implements PositionalColoredLightSource {

        public int getColoredLightEmission(IBlockAccess access, BlockPos pos, IBlockState state) {
            return 0xABC;
        }

    }
    @Test
    void gregLampsKeepAllPowerInversionLightAndBloomCombinations() {
        Map<String, Block> blocks = new HashMap<>();
        for (EnumDyeColor dye : EnumDyeColor.values()) {
            blocks.put("gregtech:" + dye.getName() + "_lamp", new GregLamp());
            blocks.put("gregtech:borderless_" + dye.getName() + "_lamp", new GregLamp());
        }
        Map<IBlockState, Integer> colors = colors(blocks);
        assertEquals(512, colors.size());
        for (var entry : blocks.entrySet()) {
            String name = entry.getKey().replace("gregtech:", "").replace("borderless_", "").replace("_lamp", "");
            EnumDyeColor dye = Arrays.stream(EnumDyeColor.values()).filter(c -> c.getName().equals(name)).findFirst()
                .orElseThrow();
            for (var state : entry.getValue().getBlockState().getValidStates()) {
                assertEquals(RgbDefaultPalette.glassTransmittance(dye), colors.get(state));
                int emitted = RgbLightAccess.effectiveEmission(RgbLight.normalize(colors.get(state)),
                    state.getLightValue(), true);
                assertEquals(state.getLightValue(),
                    Math.max(emitted >>> 8, Math.max(emitted >>> 4 & 15, emitted & 15)));
                assertEquals(state.getValue(LIGHT) && state.getValue(POWERED) == state.getValue(INVERTED),
                    emitted != 0);
            }
        }
        assertTrue(colors(Map.of("gregtech:red_lamp", new ApiLamp())).isEmpty());
    }
    @Test
    void coloredGlassAndPlasticUseDyesInsteadOfMetadataOrder() {
        for (String id : List.of("mekanism:glowplasticblock", "enderio:block_enlightened_fused_glass",
            "enderio:block_not_pasture_enlightened_fused_quartz")) {
            Dyed block = new Dyed();
            Map<IBlockState, Integer> colors = colors(Map.of(id, block));
            assertEquals(16, colors.size());
            for (EnumDyeColor dye : EnumDyeColor.values())
                assertEquals(RgbDefaultPalette.glassTransmittance(dye),
                    colors.get(block.getDefaultState().withProperty(COLOR, dye)));
        }
        assertTrue(colors(Map.of("mekanism:plasticblock", new Dyed(), "enderio:block_dark_fused_glass", new Dyed()))
            .isEmpty());
        assertTrue(colors(Map.of()).isEmpty());
    }
    static Map<IBlockState, Integer> colors(Map<String, Block> blocks) {
        Map<IBlockState, Integer> colors = new IdentityHashMap<>();
        ModLightColors.registerDefaults(id -> blocks.get(id.toString()), colors::put);
        return colors;
    }
    enum Variant implements net.minecraft.util.IStringSerializable {

        REFINED_OBSIDIAN, REFINED_GLOWSTONE, TELEPORTER_FRAME, SUPERHEATING_ELEMENT, ENERGIZED_SMELTER, HEAT_GENERATOR, GAS_GENERATOR, BIO_GENERATOR, LIGHT_GRAY, RED, UNKNOWN;
        public String getName() {
            return name().toLowerCase(Locale.ROOT);
        }

    }
    static final PropertyEnum<Variant> TYPE = PropertyEnum.create("type", Variant.class);
    static class Typed extends Block {

        Typed() {
            super(Material.IRON);
        }
        protected BlockStateContainer createBlockState() {
            return new BlockStateContainer(this, TYPE);
        }

    }
    @Test
    void industrialVariantsMatchNamesWithoutTintingUnrelatedMachineStates() {
        Map<String, Map<Variant, Integer>> expected = Map.of(
            "mekanism:basicblock",
            Map.of(Variant.REFINED_OBSIDIAN, 0xB8F, Variant.REFINED_GLOWSTONE, 0xFD3, Variant.TELEPORTER_FRAME, 0x9BE),
            "mekanism:basicblock2", Map.of(Variant.SUPERHEATING_ELEMENT, 0xFCA),
            "mekanism:machineblock", Map.of(Variant.ENERGIZED_SMELTER, 0xDCA),
            "mekanismgenerators:generator",
            Map.of(Variant.HEAT_GENERATOR, 0xFCA, Variant.GAS_GENERATOR, 0x9DF, Variant.BIO_GENERATOR, 0xBE8),
            "actuallyadditions:block_colored_lamp_on", Map.of(Variant.LIGHT_GRAY, 0xAAA, Variant.RED, 0xF00));
        for (var entry : expected.entrySet()) {
            Typed block = new Typed();
            var colors = colors(Map.of(entry.getKey(), block));
            assertEquals(entry.getValue().size(), colors.size());
            for (var state : block.getBlockState().getValidStates())
                assertEquals(entry.getValue().get(state.getValue(TYPE)), colors.get(state), entry.getKey() + state);
        }
    }

    public static class MekColor {

        int dye;
        public int getMetaValue() {
            return dye;
        }

    }
    public static class Panel extends TileEntity {

        public MekColor colour = new MekColor();

    }
    public static class Cable extends TileEntity {

        int temperature;
        public int getTemperature() {
            return temperature;
        }

    }
    @Test
    void panelSamplesColorOnlyChangesAndPreservesEmissionStrength() {
        Panel tile = new Panel();
        AtomicInteger level = new AtomicInteger(15);
        IBlockState state = state(level);
        IBlockAccess access = access(tile);
        for (EnumDyeColor dye : EnumDyeColor.values()) {
            tile.colour.dye = dye.getDyeDamage();
            assertEquals(RgbLight.normalize(RgbDefaultPalette.glassTransmittance(dye)),
                IndustrialDynamicLightColors.mekanismPanel(state, access, BlockPos.ORIGIN));
        }
        tile.colour.dye = EnumDyeColor.BLUE.getDyeDamage();
        level.set(7);
        assertEquals(0x007, IndustrialDynamicLightColors.mekanismPanel(state, access, BlockPos.ORIGIN));
        tile.colour.dye = -1;
        assertEquals(0x777, IndustrialDynamicLightColors.mekanismPanel(state, access, BlockPos.ORIGIN));
        level.set(0);
        assertEquals(0, IndustrialDynamicLightColors.mekanismPanel(state, access(null), BlockPos.ORIGIN));
    }
    @Test
    void cableTemperatureChangesHueWithoutInventingOrIncreasingLight() {
        Cable tile = new Cable();
        AtomicInteger level = new AtomicInteger(0);
        IBlockState state = state(level);
        for (int kelvin : new int[]{500, 1000, 1500, 2000, 3000, 4000, 5000, 9000}) {
            tile.temperature = kelvin;
            level.set(Math.max(0, Math.min(15, (kelvin - 500) * 15 / 4500)));
            int emitted = IndustrialDynamicLightColors.gregCable(state, access(tile), BlockPos.ORIGIN);
            assertEquals(level.get(), emitted >>> 8);
            assertTrue((emitted >>> 4 & 15) <= level.get());
            assertTrue((emitted & 15) <= level.get());
        }
        assertNotEquals(IndustrialDynamicLightColors.cableHue(1000), IndustrialDynamicLightColors.cableHue(5000));
        assertEquals(0xFDC, IndustrialDynamicLightColors.cableHue(5000));
        assertEquals(0xFFF, IndustrialDynamicLightColors.gregCable(state, access(new TileEntity() {
        }), BlockPos.ORIGIN));
    }
    static IBlockState state(AtomicInteger level) {
        return (IBlockState) Proxy.newProxyInstance(IndustrialLightColorsTest.class.getClassLoader(),
            new Class[]{IBlockState.class},
            (p, m, a) -> {
                if (m.getName().equals("getLightValue"))
                    return level.get();
                throw new AssertionError(m);
            });
    }
    static IBlockAccess access(TileEntity tile) {
        return (IBlockAccess) Proxy.newProxyInstance(IndustrialLightColorsTest.class.getClassLoader(),
            new Class[]{IBlockAccess.class},
            (p, m, a) -> {
                if (m.getName().equals("getTileEntity"))
                    return tile;
                throw new AssertionError(m);
            });
    }

}
