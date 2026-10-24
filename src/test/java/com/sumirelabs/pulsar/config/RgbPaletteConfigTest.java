package com.sumirelabs.pulsar.config;

import net.minecraft.block.BlockColored;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.item.EnumDyeColor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RgbPaletteConfigTest {
    @BeforeAll static void bootstrap() { Bootstrap.register(); }
    @Test void registryAndStateSelectorsSupportAllStatesAndLegacyMetadata() {
        assertEquals(Blocks.STAINED_GLASS.getBlockState().getValidStates(), RgbPaletteConfig.select("minecraft:stained_glass"));
        var red = Blocks.STAINED_GLASS.getDefaultState().withProperty(BlockColored.COLOR, EnumDyeColor.RED);
        assertEquals(List.of(red), RgbPaletteConfig.select("minecraft:stained_glass[color=red]"));
        assertEquals(List.of(red), RgbPaletteConfig.select("minecraft:stained_glass:" + EnumDyeColor.RED.getMetadata()));
        assertEquals(RgbPaletteConfig.select("minecraft:stained_glass"), RgbPaletteConfig.select("minecraft:stained_glass:*"));
    }
    @Test void invalidInputNeverPartiallyAppliesOrClamps() {
        List<String> warnings = new ArrayList<>();
        Map<IBlockState, Integer> applied = new HashMap<>();
        for (String row : List.of("minecraft:torch = 16,0,0", "minecraft:torch = -1,0,0", "minecraft:torch = 1.2,0,0",
                "minecraft:torch = 1,0", "missing:unknown = 1,2,3", "minecraft:stained_glass[color=nope] = 1,2,3",
                "minecraft:stained_glass[color=red,color=blue] = 1,2,3", "minecraft:air = 1,2,3"))
            RgbPaletteConfig.parse(row, true, applied::put, warnings::add);
        assertEquals(8, warnings.size()); assertTrue(applied.isEmpty());
    }
    @Test void laterStateOverridesWinAndAbsorptionZeroIsValid() {
        Map<IBlockState, Integer> applied = new HashMap<>(); List<String> warnings = new ArrayList<>();
        RgbPaletteConfig.parse("minecraft:stained_glass = 1,2,3", false, applied::put, warnings::add);
        RgbPaletteConfig.parse("minecraft:stained_glass[color=red] = 0,0,0", false, applied::put, warnings::add);
        assertEquals(16, applied.size());
        assertEquals(0, applied.get(Blocks.STAINED_GLASS.getDefaultState().withProperty(BlockColored.COLOR, EnumDyeColor.RED)));
        assertEquals(0x123, applied.get(Blocks.STAINED_GLASS.getDefaultState().withProperty(BlockColored.COLOR, EnumDyeColor.BLUE)));
        assertTrue(warnings.isEmpty());
    }
    @Test void commentsAreIgnoredAndBlackCanDisableUserRgbEmission() {
        List<String> warnings = new ArrayList<>();
        Map<IBlockState, Integer> applied = new HashMap<>();
        RgbPaletteConfig.parse("# comment", true, (s, c) -> fail(), warnings::add);
        RgbPaletteConfig.parse(" ", true, (s, c) -> fail(), warnings::add);
        RgbPaletteConfig.parse("minecraft:torch = 0,0,0", true, applied::put, warnings::add);
        assertFalse(applied.isEmpty()); assertTrue(applied.values().stream().allMatch(c -> c == 0));
        assertTrue(warnings.isEmpty());
    }
}
