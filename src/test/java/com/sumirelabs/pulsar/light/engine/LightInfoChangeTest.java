package com.sumirelabs.pulsar.light.engine;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LightInfoChangeTest {
    private static final class ContextOpacityBlock extends Block {
        ContextOpacityBlock() { super(Material.ROCK); }
        @Override public int getLightOpacity(final IBlockState state, final IBlockAccess world,
                                             final BlockPos pos) { return 1; }
    }

    @BeforeAll static void bootstrap() { Bootstrap.register(); }

    @Test
    void equalStaticLightDescriptorsSkipRedundantRechecks() {
        assertFalse(LightInfo.requiresBlockChange(Blocks.STONE.getDefaultState(), Blocks.IRON_ORE.getDefaultState()));
        assertFalse(LightInfo.requiresBlockChange(Blocks.STONE.getDefaultState(), Blocks.STONE.getDefaultState()));
        assertFalse(LightInfo.requiresBlockChange(Blocks.AIR.getDefaultState(), Blocks.GLASS.getDefaultState()));
    }

    @Test
    void opacityEmissionAndContextualInputsRequireBothLightLanesToRecheck() {
        assertTrue(LightInfo.requiresBlockChange(Blocks.AIR.getDefaultState(), Blocks.STONE.getDefaultState()));
        assertTrue(LightInfo.requiresBlockChange(Blocks.STONE.getDefaultState(), Blocks.GLOWSTONE.getDefaultState()));
        assertTrue(LightInfo.requiresBlockChange(
                Blocks.STONE.getDefaultState(), new ContextOpacityBlock().getDefaultState()));
    }
}
