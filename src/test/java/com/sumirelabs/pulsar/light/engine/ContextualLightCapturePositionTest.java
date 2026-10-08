package com.sumirelabs.pulsar.light.engine;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContextualLightCapturePositionTest {
    @BeforeAll static void bootstrap() { Bootstrap.register(); }

    private static final class MutatingBlock extends Block {
        int x, y, z;
        int opacityCalls, emissionCalls;
        Runnable nested = () -> {};

        MutatingBlock() { super(Material.ROCK); }

        private void check(BlockPos pos) {
            assertEquals(x, pos.getX());
            assertEquals(y, pos.getY());
            assertEquals(z, pos.getZ());
        }

        @Override public int getLightOpacity(IBlockState state, IBlockAccess access, BlockPos pos) {
            check(pos);
            opacityCalls++;
            nested.run();
            check(pos);
            ((BlockPos.MutableBlockPos) pos).setPos(999, 999, 999);
            return 3;
        }

        @Override public int getLightValue(IBlockState state, IBlockAccess access, BlockPos pos) {
            check(pos);
            emissionCalls++;
            ((BlockPos.MutableBlockPos) pos).setPos(-999, -999, -999);
            return 10;
        }
    }

    private static void capture(MutatingBlock block, BlockPos.MutableBlockPos scratch, int x, int y, int z) {
        block.x = x;
        block.y = y;
        block.z = z;
        IBlockState state = block.getDefaultState();
        int info = LightInfo.of(state);
        assertEquals(LightInfo.CONTEXT_MASK, info & LightInfo.CONTEXT_MASK);
        int result = LightInfo.resolveContextual(info, state, null, scratch, x, y, z);
        assertEquals(3, LightInfo.opacity(result));
        assertEquals(10, LightInfo.emission(result));
    }

    @Test void reusedPositionIsResetBetweenCallbacksCellsAndFluidLayers() {
        final var block = new MutatingBlock();
        final var fluid = new MutatingBlock();
        final var scratch = new BlockPos.MutableBlockPos();
        for (int y : new int[]{-64, 0, 319}) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    capture(block, scratch, -32 + x, y, 48 + z);
                    capture(fluid, scratch, -32 + x, y, 48 + z);
                }
            }
        }
        assertEquals(768, block.opacityCalls);
        assertEquals(768, block.emissionCalls);
        assertEquals(768, fluid.opacityCalls);
        assertEquals(768, fluid.emissionCalls);
    }

    @Test void nestedCaptureWithItsOwnLocalPositionDoesNotCorruptOuterCallback() {
        final var outer = new MutatingBlock();
        final var inner = new MutatingBlock();
        outer.nested = () -> capture(inner, new BlockPos.MutableBlockPos(), 48, -12, -7);
        final var scratch = new BlockPos.MutableBlockPos();
        capture(outer, scratch, -30, 300, 42);
        capture(outer, scratch, 7, -64, 8);
        assertEquals(2, inner.opacityCalls);
        assertEquals(2, outer.emissionCalls);
    }
}
