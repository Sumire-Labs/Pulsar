package com.sumirelabs.pulsar.compat;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.sumirelabs.pulsar.api.ColoredLightSource;
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
import net.minecraft.util.IStringSerializable;

import static org.junit.jupiter.api.Assertions.*;

class SupplementalLightColorsTest {

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

    private static final PropertyInteger LEVEL = PropertyInteger.create("level", 0, 15);
    private static class VariableLamp extends Plain {

        @Override
        protected BlockStateContainer createBlockState() {
            return new BlockStateContainer(this, LEVEL);
        }
        @Override
        public int getLightValue(IBlockState state) {
            return state.getValue(LEVEL);
        }

    }

    @Test
    void variableLampKeepsEveryNativeBrightnessAndStaysOffAtZeroInEitherPolicy() {
        Block lamp = new VariableLamp();
        var registered = colors(Map.of("charm:variable_redstone_lamp", lamp));
        assertEquals(16, registered.size());
        for (int level = 0; level <= 15; level++) {
            IBlockState state = lamp.getDefaultState().withProperty(LEVEL, level);
            assertEquals(level, state.getLightValue());
            int hue = RgbLight.normalize(registered.get(state));
            for (boolean projected : new boolean[]{false, true}) {
                int emitted = RgbLightAccess.effectiveEmission(hue, state.getLightValue(), projected);
                assertEquals(level, Math.max(emitted >> 8, Math.max(emitted >> 4 & 15, emitted & 15)));
                assertTrue((emitted >> 8) >= (emitted & 15), "Redstone light remains red");
            }
        }
    }

    private static final PropertyBool BURNING = PropertyBool.create("burning");
    private static class Kiln extends Plain {

        @Override
        protected BlockStateContainer createBlockState() {
            return new BlockStateContainer(this, BURNING);
        }
        @Override
        public int getLightValue(IBlockState state) {
            return state.getValue(BURNING) ? 13 : 0;
        }

    }

    @Test
    void kilnPresetSelectsOnlyTheBurningStateAndSkipsUnknownStateLayouts() {
        Block kiln = new Kiln();
        var registered = colors(Map.of("embersconstruct:kiln", kiln));
        IBlockState lit = kiln.getDefaultState().withProperty(BURNING, true);
        IBlockState off = kiln.getDefaultState().withProperty(BURNING, false);
        assertEquals(Map.of(lit, 0xDCA), registered);
        assertEquals(0, off.getLightValue());
        assertEquals(13, lit.getLightValue());
        assertTrue(colors(Map.of("embersconstruct:kiln", new Plain())).isEmpty());
    }

    private enum Variant implements IStringSerializable {

        CANDLE_WHITE, CANDLE_RED, CANDLE_LIGHT_BLUE, CANDLE_BLACK, CRYSTAL_WHITE, CRYSTAL_RED, CRYSTAL_BLUE, CRYSTAL_INDIGO, CRYSTAL_BLACK, UNKNOWN;
        @Override
        public String getName() {
            return name().toLowerCase(Locale.ROOT);
        }

    }
    private static final PropertyEnum<Variant> VARIANT = PropertyEnum.create("variant", Variant.class);
    private static class VariantBlock extends Plain {

        @Override
        protected BlockStateContainer createBlockState() {
            return new BlockStateContainer(this, VARIANT);
        }

    }

    @Test
    void quarkUsesItsSerializedVariantsAndDistinguishesCyanBlueFromIndigo() {
        Block candle = new VariantBlock().setLightLevel(0.9375f);
        Block crystal = new VariantBlock().setLightLevel(11f / 15);
        Block pane = new VariantBlock().setLightLevel(11f / 15);
        var registered = colors(Map.of("quark:candle", candle, "quark:crystal", crystal, "quark:crystal_pane", pane));
        assertEquals(0xF00, registered.get(candle.getDefaultState().withProperty(VARIANT, Variant.CANDLE_RED)));
        assertFalse(registered.containsKey(candle.getDefaultState().withProperty(VARIANT, Variant.CRYSTAL_RED)));
        for (Block block : new Block[]{crystal, pane}) {
            assertEquals(0x0FF, registered.get(block.getDefaultState().withProperty(VARIANT, Variant.CRYSTAL_BLUE)));
            assertEquals(0x00F, registered.get(block.getDefaultState().withProperty(VARIANT, Variant.CRYSTAL_INDIGO)));
            IBlockState black = block.getDefaultState().withProperty(VARIANT, Variant.CRYSTAL_BLACK);
            assertEquals(0xFFF, registered.get(black), "Black stays neutral rather than losing its native light");
            assertEquals(11, black.getLightValue());
            assertFalse(registered.containsKey(block.getDefaultState().withProperty(VARIANT, Variant.UNKNOWN)));
        }
        assertTrue(colors(Map.of("quark:candle", new Plain(), "quark:crystal", new Plain())).isEmpty());
    }

    private enum Device implements IStringSerializable {

        BALLOON, LANTERN, CUSHION;
        @Override
        public String getName() {
            return name().toLowerCase(Locale.ROOT);
        }

    }
    private static final PropertyEnum<Device> TYPE = PropertyEnum.create("type", Device.class);
    private static class DeviceBlock extends Plain {

        @Override
        protected BlockStateContainer createBlockState() {
            return new BlockStateContainer(this, TYPE);
        }

    }

    @Test
    void immersiveEngineeringOnlyColorsTheLanternAndBalloonVariants() {
        Block cloth = new DeviceBlock(), metal = new DeviceBlock();
        var registered = colors(Map.of("immersiveengineering:cloth_device", cloth,
            "immersiveengineering:metal_decoration2", metal));
        assertEquals(Map.of(cloth.getDefaultState().withProperty(TYPE, Device.BALLOON), 0xDA8,
            metal.getDefaultState().withProperty(TYPE, Device.LANTERN), 0xDA8), registered);
        assertTrue(colors(Map.of("immersiveengineering:cloth_device", new Plain())).isEmpty());
    }

    @Test
    void numberedCandlesAndBritishSpelledSeaLanternIdsKeepTheirNativeLight() {
        Block candle = new Plain().setLightLevel(0.6f);
        Block lantern = new Plain().setLightLevel(1);
        var registered = colors(Map.of("harvestcraft:candledeco15", candle,
            "colouredsealanterns:sealanternlight_gray", lantern,
            "colouredsealanterns:sealanternsilver", new Plain()));
        assertEquals(2, registered.size());
        assertEquals(0xF00, registered.get(candle.getDefaultState()));
        assertEquals(0xAAA, registered.get(lantern.getDefaultState()));
        assertEquals(9, candle.getDefaultState().getLightValue());
        assertEquals(15, lantern.getDefaultState().getLightValue());
    }

    private static class CustomSource extends Plain implements ColoredLightSource {

        @Override
        public int getColoredLightEmission(IBlockState state) {
            return 0x246;
        }

    }

    private enum Lights implements IStringSerializable {

        WHITE, RED, GREEN, BLUE, MULTICOLOR;
        @Override
        public String getName() {
            return name().toLowerCase(Locale.ROOT);
        }

    }
    private static final PropertyEnum<Lights> LIGHTS = PropertyEnum.create("lights", Lights.class);
    private static class EnlightenedBush extends Plain {

        @Override
        protected BlockStateContainer createBlockState() {
            return new BlockStateContainer(this, LIGHTS);
        }

    }

    @Test
    void inspirationsUsesTheLightsPropertyAndLeavesMixedPatternsUnchanged() {
        Block bush = new EnlightenedBush().setLightLevel(1);
        var registered = colors(Map.of("inspirations:enlightened_bush", bush));
        assertEquals(4, registered.size());
        assertEquals(0xF00, registered.get(bush.getDefaultState().withProperty(LIGHTS, Lights.RED)));
        assertEquals(0x00F, registered.get(bush.getDefaultState().withProperty(LIGHTS, Lights.BLUE)));
        assertFalse(registered.containsKey(bush.getDefaultState().withProperty(LIGHTS, Lights.MULTICOLOR)));
        assertEquals(15, bush.getDefaultState().getLightValue());
        assertTrue(colors(Map.of("inspirations:enlightened_bush", new Plain())).isEmpty());
    }

    @Test
    void optionalPresetsNeverCreateLightOrReplaceAModsOwnColorApi() {
        Map<String, Block> blocks = new HashMap<>();
        for (String id : new String[]{"quark:paper_lantern", "embers:archaic_light", "netherex:blue_fire",
            "deepmoblearning:simulation_chamber", "adchimneys:glowstone"})
            blocks.put(id, new Plain());
        blocks.put("quark:blaze_lantern", new CustomSource());
        var registered = colors(blocks);
        assertEquals(5, registered.size());
        for (var entry : registered.entrySet()) {
            assertEquals(0, entry.getKey().getLightValue());
            assertEquals(0, RgbLightAccess.effectiveEmission(RgbLight.normalize(entry.getValue()), 0, true));
        }
        assertFalse(registered.containsKey(blocks.get("quark:blaze_lantern").getDefaultState()));
        assertTrue(colors(Map.of()).isEmpty());
    }

}
