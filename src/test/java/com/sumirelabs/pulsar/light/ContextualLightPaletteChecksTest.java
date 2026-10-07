package com.sumirelabs.pulsar.light;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.chunk.BlockStatePaletteHashMap;
import net.minecraft.world.chunk.BlockStatePaletteLinear;
import net.minecraft.world.chunk.BlockStatePaletteRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContextualLightPaletteChecksTest {
    @BeforeAll static void bootstrap() { Bootstrap.register(); }

    private static final class ContextEmissionBlock extends Block {
        ContextEmissionBlock() { super(Material.ROCK); }
        @Override public int getLightValue(IBlockState state, IBlockAccess world, BlockPos pos) { return 10; }
    }

    private static final class ContextOpacityBlock extends Block {
        ContextOpacityBlock() { super(Material.ROCK); }
        @Override public int getLightOpacity(IBlockState state, IBlockAccess world, BlockPos pos) { return 3; }
    }

    private static final class StaticBlock extends Block {
        StaticBlock() { super(Material.ROCK); }
    }

    @Test void contextualStateAtTheLastLocalPaletteIdIsDetected() {
        final var linear = new BlockStatePaletteLinear(4, null);
        for (int i = 0; i < 15; i++) linear.idFor(new StaticBlock().getDefaultState());
        assertEquals(15, linear.idFor(new ContextEmissionBlock().getDefaultState()));
        assertTrue(ContextualLightPaletteChecks.mayNeedSamples(linear));
        final var hashed = new BlockStatePaletteHashMap(8, null);
        for (int i = 0; i < 255; i++) hashed.idFor(new StaticBlock().getDefaultState());
        assertFalse(ContextualLightPaletteChecks.mayNeedSamples(hashed));
        assertEquals(255, hashed.idFor(new ContextOpacityBlock().getDefaultState()));
        assertTrue(ContextualLightPaletteChecks.mayNeedSamples(hashed));
    }

    @Test void staticVanillaStatesNeedNoPositionSamples() {
        final var palette = new BlockStatePaletteLinear(4, null);
        palette.idFor(Blocks.AIR.getDefaultState());
        palette.idFor(Blocks.STONE.getDefaultState());
        palette.idFor(Blocks.GLOWSTONE.getDefaultState());
        assertFalse(ContextualLightPaletteChecks.mayNeedSamples(palette));
        assertTrue((ContextualLightPaletteChecks.flags(palette)
                & ContextualLightPalette.MAY_EMIT) != 0);
    }

    @Test void paletteWithoutEmittersCanSkipTheInitialSourceScan() {
        final var palette = new BlockStatePaletteLinear(4, null);
        palette.idFor(Blocks.AIR.getDefaultState());
        palette.idFor(Blocks.STONE.getDefaultState());
        assertEquals(0, ContextualLightPaletteChecks.flags(palette)
                & ContextualLightPalette.MAY_EMIT);
    }

    @Test void contextualEmissionCannotBeSkippedEvenWithStaticEmissionZero() {
        final var palette = new BlockStatePaletteLinear(4, null);
        palette.idFor(Blocks.STONE.getDefaultState());
        palette.idFor(new ContextEmissionBlock().getDefaultState());
        assertTrue(ContextualLightPaletteChecks.mayNeedSamples(palette));
        final int flags = ContextualLightPaletteChecks.flags(palette);
        assertNotEquals(0, flags & ContextualLightPalette.CONTEXT_EMISSION);
        assertNotEquals(0, flags & ContextualLightPalette.MAY_EMIT);
    }

    @Test void contextualOpacityInAHashPaletteRetainsSampling() {
        final var palette = new BlockStatePaletteHashMap(5, null);
        palette.idFor(Blocks.AIR.getDefaultState());
        palette.idFor(Blocks.STONE.getDefaultState());
        assertFalse(ContextualLightPaletteChecks.mayNeedSamples(palette));
        palette.idFor(new ContextOpacityBlock().getDefaultState());
        assertTrue(ContextualLightPaletteChecks.mayNeedSamples(palette));
        final int flags = ContextualLightPaletteChecks.flags(palette);
        assertNotEquals(0, flags & ContextualLightPalette.CONTEXT_OPACITY);
        assertEquals(0, flags & ContextualLightPalette.MAY_EMIT);
    }

    @Test void reclassifiesAPaletteAfterABlockIsAdded() {
        final var palette = new BlockStatePaletteLinear(4, null);
        palette.idFor(Blocks.STONE.getDefaultState());
        assertFalse(ContextualLightPaletteChecks.mayNeedSamples(palette));
        palette.idFor(new ContextEmissionBlock().getDefaultState());
        assertTrue(ContextualLightPaletteChecks.mayNeedSamples(palette));
    }

    @Test void unknownAndSubclassedPalettesTakeTheConservativePath() {
        assertTrue(ContextualLightPaletteChecks.mayNeedSamples(null));
        assertEquals(ContextualLightPalette.CONSERVATIVE_FLAGS,
                ContextualLightPaletteChecks.flags(null));
        assertTrue(ContextualLightPaletteChecks.mayNeedSamples(new BlockStatePaletteRegistry()));
        assertEquals(ContextualLightPalette.CONSERVATIVE_FLAGS,
                ContextualLightPaletteChecks.flags(new BlockStatePaletteRegistry()));
        final var palette = new BlockStatePaletteLinear(4, null) {
            @Override public IBlockState getBlockState(int index) { fail("Custom palette must not be probed"); return null; }
        };
        assertTrue(ContextualLightPaletteChecks.mayNeedSamples(palette));
        assertEquals(ContextualLightPalette.CONSERVATIVE_FLAGS,
                ContextualLightPaletteChecks.flags(palette));
    }
}
