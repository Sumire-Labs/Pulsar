package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.light.engine.LightInfo;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraftforge.fml.common.Loader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContextualLightManagerCaptureTest {
    private static java.lang.reflect.Field namedMods;
    private static Object previousMods;

    @BeforeAll static void bootstrap() throws Exception {
        Bootstrap.register();
        // This test uses real chunks without starting Forge's mod discovery.
        namedMods = Loader.class.getDeclaredField("namedMods");
        namedMods.setAccessible(true);
        previousMods = namedMods.get(Loader.instance());
        if (previousMods == null) namedMods.set(Loader.instance(), java.util.Map.of());
    }

    @AfterAll static void restoreLoader() throws Exception {
        namedMods.set(Loader.instance(), previousMods);
    }

    private static final class ContextBlock extends Block {
        int calls;
        ContextBlock() { super(Material.ROCK); }
        @Override public int getLightValue(IBlockState state, IBlockAccess access, BlockPos pos) {
            calls++;
            int value = pos.getX() & 15;
            ((BlockPos.MutableBlockPos) pos).setPos(999, 999, 999);
            return value;
        }
    }

    private static final class TestChunk extends Chunk {
        int lookups;
        TestChunk(int x, int z) { super(null, x, z); }
        @Override public IBlockState getBlockState(int x, int y, int z) {
            lookups++;
            // Use real section storage without World's debug-world lookup.
            ExtendedBlockStorage[] sections = this.getBlockStorageArray();
            int index = y >> 4;
            if (index < 0 || index >= sections.length || sections[index] == null) {
                return Blocks.AIR.getDefaultState();
            }
            return sections[index].get(x & 15, y & 15, z & 15);
        }
    }

    @Test void staticChangesSkipDiscoveryUntilTheWorldHasContextualSources() throws Exception {
        final var chunk = new TestChunk(-2, 3);
        final var section = new ExtendedBlockStorage(64, true);
        chunk.getBlockStorageArray()[4] = section;
        section.set(0, 3, 7, Blocks.STONE.getDefaultState());
        final var manager = new ContextualLightManager(null, WorldHeightContext.VANILLA);
        manager.load(chunk);
        int before = chunk.lookups;
        manager.requestBlockChange(Blocks.AIR.getDefaultState(), Blocks.GLOWSTONE.getDefaultState(),
                -25, 67, 55);
        assertEquals(before, chunk.lookups);
        assertFalse(manager.hasPending(-2, 3));

        var block = new ContextBlock();
        section.set(6, 3, 7, block.getDefaultState());
        manager.load(chunk);
        manager.requestBlockChange(Blocks.AIR.getDefaultState(), Blocks.GLOWSTONE.getDefaultState(),
                -25, 67, 55);
        assertTrue(manager.hasPending(-2, 3), "Existing contextual neighbours still need invalidation");
        before = chunk.lookups;
        manager.unload(-2, 3);
        manager.requestBlockChange(Blocks.AIR.getDefaultState(), Blocks.GLOWSTONE.getDefaultState(),
                -25, 67, 55);
        assertEquals(before, chunk.lookups);
    }

    @Test void chunkLoadCapturesEachContextualCellBeforeWorkersReadIt() {
        final var block = new ContextBlock();
        final IBlockState state = block.getDefaultState();
        final Chunk chunk = new TestChunk(-2, 3);
        final var section = new ExtendedBlockStorage(64, true);
        chunk.getBlockStorageArray()[4] = section;
        for (int x = 0; x < 16; x++) section.set(x, 3, 7, state);
        final var manager = new ContextualLightManager(null, WorldHeightContext.VANILLA);
        manager.load(chunk);
        assertEquals(16, block.calls);
        final int staticInfo = LightInfo.of(state);
        for (int x = 0; x < 16; x++) {
            assertEquals(x, LightInfo.emission(manager.read(staticInfo, state, -32 + x, 67, 55)));
        }
        assertEquals(16, block.calls, "Reads must consume samples without invoking block callbacks");
        assertFalse(manager.hasPending(-2, 3));
        manager.unload(-2, 3);
        assertEquals(staticInfo, manager.read(staticInfo, state, -32, 67, 55));
    }

    @Test void verticalRangeInvalidatesSourcesAcrossNegativeChunkBoundaries() {
        final var block = new ContextBlock();
        final var manager = new ContextualLightManager(null, WorldHeightContext.VANILLA);
        final int[][] positions = {{-1, 0, 15, 0}, {0, 0, 0, 0}, {-1, -1, 15, 15}};
        for (int[] position : positions) {
            final var chunk = new TestChunk(position[0], position[1]);
            final var section = new ExtendedBlockStorage(64, true);
            chunk.getBlockStorageArray()[4] = section;
            section.set(position[2], 3, position[3], block.getDefaultState());
            manager.load(chunk);
        }
        final int calls = block.calls;
        manager.requestVerticalRange(-1, 0, 67, 67);
        for (int[] position : positions) assertTrue(manager.hasPending(position[0], position[1]));
        assertEquals(calls, block.calls, "Column invalidation must never call live block callbacks");
        assertFalse(manager.hasPending(0, -1), "Diagonal chunks are outside the original neighbourhood");
    }

    @Test void verticalRangeRequestsOnlyStoredNeighbourSourcesWithoutCallingBlocks() throws Exception {
        final var block = new ContextBlock();
        final Chunk chunk = new TestChunk(-2, 3);
        final var section = new ExtendedBlockStorage(64, true);
        chunk.getBlockStorageArray()[4] = section;
        for (int x = 0; x < 16; x++) section.set(x, 3, 7, block.getDefaultState());
        final var manager = new ContextualLightManager(null, WorldHeightContext.VANILLA);
        manager.load(chunk);
        int calls = block.calls;
        manager.requestVerticalRange(-25, 55, 68, 66);
        assertEquals(calls, block.calls);
        var entriesField = ContextualLightManager.class.getDeclaredField("chunks");
        entriesField.setAccessible(true);
        Object entry = ((java.util.Map<?, ?>) entriesField.get(manager)).values().iterator().next();
        var snapshotField = entry.getClass().getDeclaredField("snapshot");
        snapshotField.setAccessible(true);
        var snapshot = (ContextualLightSnapshot<?>) snapshotField.get(entry);
        assertEquals(java.util.Set.of((67 << 8) | (7 << 4) | 6,
                (67 << 8) | (7 << 4) | 7, (67 << 8) | (7 << 4) | 8), snapshot.takePending());
        manager.requestVerticalRange(-25, 55, 200, 210);
        assertFalse(manager.hasPending(-2, 3));
    }
}
