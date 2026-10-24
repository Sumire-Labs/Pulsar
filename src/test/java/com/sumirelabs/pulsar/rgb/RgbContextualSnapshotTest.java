package com.sumirelabs.pulsar.rgb;

import com.sumirelabs.pulsar.api.*;
import com.sumirelabs.pulsar.light.ContextualLightSnapshot;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class RgbContextualSnapshotTest {
    @BeforeAll static void bootstrap() { Bootstrap.register(); }
    private static final class DynamicBlock extends Block implements PositionalColoredLightSource, PositionalColoredTranslucency {
        DynamicBlock() { super(Material.ROCK); }
        public int getColoredLightEmission(IBlockAccess world, BlockPos pos, IBlockState state) {
            throw new AssertionError("Static classification must never invoke a world callback");
        }
        public int getColoredTransmittance(IBlockAccess world, BlockPos pos, IBlockState state) {
            throw new AssertionError("Static classification must never invoke a world callback");
        }
    }
    @Test void classificationDoesNotInvokePositionalCallbacksOrClaimAStaticFilterValue() {
        IBlockState state = new DynamicBlock().getDefaultState();
        int properties = RgbStateProperties.compute(state);
        assertEquals(RgbStateProperties.DYNAMIC, properties & RgbStateProperties.DYNAMIC);
        assertEquals(0, RgbStateProperties.filter(properties));
        assertTrue(BlockLightFilters.mayFilterSky(state));
    }
    @Test void colorOnlyChangesQueueBlockLightEvenWithUnchangedScalarBrightness() {
        var snapshot = new ContextualLightSnapshot<Object>(); Object state = new Object();
        int mask = RgbStateProperties.DYNAMIC_SOURCE;
        snapshot.publishChanges(0, state, mask | 0xF00, null, 0, 0xFFF);
        assertEquals(ContextualLightSnapshot.BLOCK_CHANGED,
                snapshot.publishChanges(0, state, mask | 0x00F, null, 0, 0xFFF));
        assertEquals(mask | 0x00F, snapshot.read(0, state, -1));
        assertEquals(0, snapshot.publishChanges(0, state, mask | 0x00F, null, 0, 0xFFF));
    }
    @Test void filterChangesRequeueBothLanesAndZeroRgbIsARealSample() {
        var snapshot = new ContextualLightSnapshot<Object>(); Object state = new Object();
        int mask = RgbStateProperties.DYNAMIC;
        snapshot.publishChanges(0, state, mask | 0xF00 | 0xFF0 << 12, null, 0, 0xFFF);
        assertEquals(ContextualLightSnapshot.BOTH_CHANGED,
                snapshot.publishChanges(0, state, mask | 0x00F << 12, null, 0, 0xFFF));
        assertEquals(0, RgbStateProperties.color(snapshot.read(0, state, -1)));
    }
    @Test void replacedBlocksCannotInheritOldFluidOrSourceColorsAndMissesAreCorrected() throws Exception {
        var snapshot = new ContextualLightSnapshot<Object>(); Object state = new Object(), fluid = new Object(), replacement = new Object();
        snapshot.publishChanges(-1, state, 0xF00, fluid, 0x00F, 0xFFF);
        try (var worker = Executors.newSingleThreadExecutor()) {
            assertEquals(0xF00, worker.submit(() -> snapshot.read(-1, state, 0xFFF)).get(5, TimeUnit.SECONDS));
            assertEquals(0x00F, worker.submit(() -> snapshot.read(-1, fluid, 0xFFF)).get(5, TimeUnit.SECONDS));
            assertEquals(0xFFF, worker.submit(() -> snapshot.read(-1, replacement, 0xFFF)).get(5, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> worker.submit(() -> snapshot.publishChanges(-1, replacement, 0, null, 0, 0xFFF)).get(5, TimeUnit.SECONDS));
        }
        assertTrue(snapshot.hasPending());
        snapshot.takePending();
        assertEquals(ContextualLightSnapshot.BOTH_CHANGED,
                snapshot.publishChanges(-1, replacement, 0, null, 0, 0xFFF));
        assertEquals(0, snapshot.read(-1, replacement, 0xFFF));
    }
}
