package com.sumirelabs.pulsar.compat;

import com.sumirelabs.pulsar.rgb.RgbPaletteOverrides;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MultipartLightBridgeTest {
    public static final class DynamicMaterial extends codechicken.microblock.BlockMicroMaterial {
        int level = 7;
        DynamicMaterial() { super(Blocks.GLOWSTONE.getDefaultState(), "pulsar:dynamic"); }
        @Override public int getLightValue() { return level; }
    }
    private static DynamicMaterial dynamic;
    @BeforeAll static void setup() throws Exception {
        Bootstrap.register();
        MultipartFixtures.material("pulsar:glowstone", Blocks.GLOWSTONE.getDefaultState());
        MultipartFixtures.material("pulsar:sea_lantern", Blocks.SEA_LANTERN.getDefaultState());
        MultipartFixtures.material("pulsar:stone", Blocks.STONE.getDefaultState());
        dynamic = new DynamicMaterial();
        codechicken.microblock.MicroMaterialRegistry.registerMaterial(dynamic, "pulsar:dynamic");
        MultipartFixtures.finishMaterials();
    }

    @Test void realMaterialGettersUsePaletteAndMixChannelsWithoutInventingEmission() {
        var old = RgbPaletteOverrides.current().values;
        try {
            RgbPaletteOverrides.install(new RgbPaletteOverrides.Values(Map.of(
                    Blocks.GLOWSTONE.getDefaultState(), 0xF00,
                    Blocks.SEA_LANTERN.getDefaultState(), 0x00F,
                    Blocks.STONE.getDefaultState(), 0x0F0), Map.of()));
            var red = MultipartFixtures.part("pulsar:glowstone");
            var blue = MultipartFixtures.part("pulsar:sea_lantern");
            assertEquals(0xF00, MultipartLightBridge.colors(List.of(red)));
            assertEquals(0xF0F, MultipartLightBridge.colors(List.of(red, blue)));
            assertEquals(0, MultipartLightBridge.colors(List.of(MultipartFixtures.part("pulsar:stone"))));
            assertFalse(MultipartLightBridge.needsPolling(List.of(red, blue)));
            assertTrue(MultipartLightBridge.needsPolling(List.of(red, new MultipartFixtures.UnknownPart(4))));
            assertEquals(0xF44, MultipartLightBridge.colors(List.of(red, new MultipartFixtures.UnknownPart(4))));
            assertEquals(0, MultipartLightBridge.colors(List.of()));
            // A palette reload must affect parts without replacing a block or its material.
            RgbPaletteOverrides.install(new RgbPaletteOverrides.Values(Map.of(Blocks.GLOWSTONE.getDefaultState(), 0x0F0), Map.of()));
            assertEquals(0x0F0, MultipartLightBridge.colors(List.of(red)));
            RgbPaletteOverrides.install(new RgbPaletteOverrides.Values(Map.of(Blocks.GLOWSTONE.getDefaultState(), 0), Map.of()));
            assertEquals(0, MultipartLightBridge.colors(List.of(red)));
        } finally { RgbPaletteOverrides.install(old); }
    }

    @Test void unknownPartsKeepDynamicWhiteEmissionAndFailuresKeepPolling() {
        var part = new MultipartFixtures.UnknownPart(11);
        assertEquals(0xBBB, MultipartLightBridge.colors(List.of(part)));
        part.level = 0;
        assertEquals(0, MultipartLightBridge.colors(List.of(part)));
        assertTrue(MultipartLightBridge.needsPolling(List.of(part)));
        assertEquals(-1, MultipartLightBridge.colors(List.of(new Object())));
        assertTrue(MultipartLightBridge.needsPolling(List.of(new Object())));
    }

    @Test void customMaterialsPreserveTheirOwnPowerAndNeverDisablePolling() {
        var old = RgbPaletteOverrides.current().values;
        try {
            RgbPaletteOverrides.install(new RgbPaletteOverrides.Values(Map.of(Blocks.GLOWSTONE.getDefaultState(),0xF00),Map.of()));
            var part = MultipartFixtures.part("pulsar:dynamic");
            dynamic.level=7;
            assertEquals(0x700,MultipartLightBridge.colors(List.of(part)));
            assertTrue(MultipartLightBridge.needsPolling(List.of(part)));
            dynamic.level=0;
            assertEquals(0,MultipartLightBridge.colors(List.of(part)));
        } finally { RgbPaletteOverrides.install(old); }
    }
}
