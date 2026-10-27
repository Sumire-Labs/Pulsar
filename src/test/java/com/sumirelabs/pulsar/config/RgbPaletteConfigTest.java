package com.sumirelabs.pulsar.config;

import net.minecraft.block.BlockColored;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.item.EnumDyeColor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RgbPaletteConfigTest {
    @TempDir java.nio.file.Path directory;
    @Test void runtimeReadStagesBothFilesAndRejectsBadOrMissingInputWithoutPublication() throws Exception {
        var before=com.sumirelabs.pulsar.rgb.RgbPaletteOverrides.current();
        java.nio.file.Files.writeString(directory.resolve("pulsar-colors.cfg"),"minecraft:torch = 8,4,2\n");
        java.nio.file.Files.writeString(directory.resolve("pulsar-filters.cfg"),"minecraft:glass = 0,15,15\n");
        var values=RgbPaletteConfig.prepareReload(directory);
        assertEquals(0xF84,values.colors().get(Blocks.TORCH.getDefaultState()));
        assertEquals(0x0FF,values.filters().get(Blocks.GLASS.getDefaultState()));
        assertSame(before,com.sumirelabs.pulsar.rgb.RgbPaletteOverrides.current());
        java.nio.file.Files.writeString(directory.resolve("pulsar-filters.cfg"),"minecraft:glass = 0,15,15\nmissing:block = 1,2,3\n");
        assertThrows(IllegalArgumentException.class,()->RgbPaletteConfig.prepareReload(directory));
        assertSame(before,com.sumirelabs.pulsar.rgb.RgbPaletteOverrides.current());
        java.nio.file.Files.delete(directory.resolve("pulsar-filters.cfg"));
        assertThrows(java.io.IOException.class,()->RgbPaletteConfig.prepareReload(directory));
        java.nio.file.Files.writeString(directory.resolve("pulsar-filters.cfg"),"");
        java.nio.file.Files.writeString(directory.resolve("pulsar-colors.cfg"),"");
        assertTrue(RgbPaletteConfig.prepareReload(directory).colors().isEmpty());
    }
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
        RgbPaletteConfig.parse("\uFEFF# UTF-8 BOM comment", true, (s,c)->fail(),warnings::add);
        RgbPaletteConfig.parse(" ", true, (s, c) -> fail(), warnings::add);
        RgbPaletteConfig.parse("\uFEFFminecraft:torch = 0,0,0", true, applied::put, warnings::add);
        assertFalse(applied.isEmpty()); assertTrue(applied.values().stream().allMatch(c -> c == 0));
        assertTrue(warnings.isEmpty());
    }
}
