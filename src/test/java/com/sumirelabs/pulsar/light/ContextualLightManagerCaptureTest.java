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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import com.sumirelabs.pulsar.config.PulsarConfig;

import static org.junit.jupiter.api.Assertions.*;

class ContextualLightManagerCaptureTest {
    private static java.lang.reflect.Field namedMods;
    private static Object previousMods;
    private int oldLimit, oldBudget;
    private boolean oldTracking;

    @BeforeEach void savePollingConfig() {
        oldLimit = PulsarConfig.performance.tileEntityLightSamplesPerTick;
        oldBudget = PulsarConfig.performance.tileEntityLightBudgetMicros;
        oldTracking = PulsarConfig.features.trackTileEntityLight;
    }

    @AfterEach void restorePollingConfig() {
        PulsarConfig.performance.tileEntityLightSamplesPerTick = oldLimit;
        PulsarConfig.performance.tileEntityLightBudgetMicros = oldBudget;
        PulsarConfig.features.trackTileEntityLight = oldTracking;
    }

    @Test
    @EnabledIfSystemProperty(named = "pulsar.benchmark.contextual", matches = "true")
    void stationaryTileSourceBenchmark() {
        if (Boolean.getBoolean("pulsar.benchmark.unlimited")) {
            PulsarConfig.performance.tileEntityLightSamplesPerTick = 1000000;
            PulsarConfig.performance.tileEntityLightBudgetMicros = 50000;
        }
        var block = new ContextBlock();
        var manager = new ContextualLightManager(null, WorldHeightContext.VANILLA);
        for (int c = 0; c < 768; c++) {
            var chunk = new TestChunk(c % 32, c / 32);
            var section = new ExtendedBlockStorage(64, true);
            chunk.getBlockStorageArray()[4] = section;
            for (int n = 0; n < 89; n++) {
                int x = n & 15, z = n >> 4;
                section.set(x, 0, z, block.getDefaultState());
                chunk.getTileEntityMap().put(new BlockPos(chunk.x * 16 + x, 64, chunk.z * 16 + z),
                        new net.minecraft.tileentity.TileEntity() {});
            }
            manager.load(chunk);
        }
        for (int i = 0; i < 40; i++) manager.flush(null);
        int before = block.calls;
        long[] timings = new long[100];
        for (int i = 0; i < timings.length; i++) {
            long start = System.nanoTime();
            manager.flush(null);
            timings[i] = System.nanoTime() - start;
        }
        java.util.Arrays.sort(timings);
        System.out.println("CONTEXTUAL_BENCH sources=68352 medianNs=" + timings[50]
                + " p95Ns=" + timings[95] + " capturesPerTick=" + (block.calls - before) / 100.0);
    }

    private static final class TileLightBlock extends Block {
        RuntimeException failure;
        int emission, opacity = 255, calls;
        Runnable callback;
        java.util.Set<BlockPos> visited;
        TileLightBlock() { super(Material.ROCK); }
        @Override public int getLightValue(IBlockState state, IBlockAccess access, BlockPos pos) {
            calls++;
            if(failure!=null)throw failure;
            if (visited != null) visited.add(pos.toImmutable());
            if (callback != null) { Runnable action = callback; callback = null; action.run(); }
            return emission;
        }
        @Override public int getLightOpacity(IBlockState state, IBlockAccess access, BlockPos pos) { return opacity; }
    }

    private static TestChunk tileChunk(TileLightBlock block, int cx, int count) {
        var chunk = new TestChunk(cx, 0);
        var section = new ExtendedBlockStorage(64, true);
        chunk.getBlockStorageArray()[4] = section;
        for (int n = 0; n < count; n++) {
            section.set(n & 15, 0, n >> 4, block.getDefaultState());
            chunk.getTileEntityMap().put(new BlockPos(cx * 16 + (n & 15), 64, n >> 4),
                    new net.minecraft.tileentity.TileEntity() {});
        }
        return chunk;
    }

    private static void pollingLimit(int count) {
        PulsarConfig.features.trackTileEntityLight = true;
        PulsarConfig.performance.tileEntityLightSamplesPerTick = count;
        PulsarConfig.performance.tileEntityLightBudgetMicros = 50000;
    }

    @Test void callbackFailuresKeepPreviousLightBackOffAndRecover() {
        pollingLimit(10);
        var block=new TileLightBlock();block.emission=8;
        var chunk=tileChunk(block,0,1);
        var manager=new ContextualLightManager(null,WorldHeightContext.VANILLA);
        manager.load(chunk);
        block.failure=new NullPointerException("Tile not installed yet");
        manager.request(0,64,0);
        int before=block.calls;
        for(int i=0;i<100;i++)manager.flushSamples((x,y,z,lanes)->fail("Failed sample replaced valid light"));
        assertTrue(block.calls-before<12,"Permanent failure must not run every tick");
        assertTrue(manager.hasPending(0,0),"Fallback light must not be saved as valid");
        assertEquals(8,LightInfo.emission(manager.read(LightInfo.of(block.getDefaultState()),block.getDefaultState(),0,64,0)));
        block.failure=null;block.emission=12;
        var changes=new java.util.ArrayList<Integer>();
        for(int i=0;i<33;i++)manager.flushSamples((x,y,z,lanes)->changes.add(lanes));
        assertEquals(12,LightInfo.emission(manager.read(LightInfo.of(block.getDefaultState()),block.getDefaultState(),0,64,0)));
        assertFalse(manager.hasPending(0,0));assertFalse(changes.isEmpty());
    }

    @Test void firstCaptureFailureRetriesEvenWithoutATileOrPolling() {
        var block=new TileLightBlock();block.failure=new IllegalStateException("forming");
        var chunk=tileChunk(block,0,1);chunk.getTileEntityMap().clear();
        PulsarConfig.features.trackTileEntityLight=false;
        var manager=new ContextualLightManager(null,WorldHeightContext.VANILLA);
        manager.load(chunk);assertTrue(manager.hasPending(0,0));
        block.failure=null;block.emission=9;
        manager.flushSamples((x,y,z,lanes)->{});
        assertEquals(9,LightInfo.emission(manager.read(LightInfo.of(block.getDefaultState()),block.getDefaultState(),0,64,0)));
        manager.unload(0,0);manager.flushSamples((x,y,z,lanes)->fail("Unloaded retry survived"));
    }

    @Test void boundedPollingIsFairAndStationarySourcesCreateNoQueuedWork() {
        pollingLimit(3);
        var block = new TileLightBlock();
        var chunk = tileChunk(block, 0, 10);
        var manager = new ContextualLightManager(null, WorldHeightContext.VANILLA);
        manager.load(chunk);
        int before = block.calls;
        var changes = new java.util.ArrayList<BlockPos>();
        manager.flushSamples((x,y,z,lanes) -> changes.add(new BlockPos(x,y,z)));
        assertEquals(3, block.calls - before);
        assertTrue(changes.isEmpty());
        assertFalse(manager.hasPending(0,0));
        block.emission = 12;
        for (int tick = 0; tick < 4; tick++) manager.flushSamples((x,y,z,lanes) -> changes.add(new BlockPos(x,y,z)));
        for (int x = 0; x < 10; x++) {
            assertEquals(12, LightInfo.emission(manager.read(LightInfo.of(block.getDefaultState()), block.getDefaultState(), x,64,0)));
        }
        assertEquals(10, new java.util.HashSet<>(changes).size(), "Every unnotified source must eventually be sampled");
    }

    @Test void explicitChangesBypassTheFallbackLimitAndWorkWithPollingDisabled() {
        pollingLimit(1);
        var block = new TileLightBlock();
        var chunk = tileChunk(block, 0, 10);
        var manager = new ContextualLightManager(null, WorldHeightContext.VANILLA);
        manager.load(chunk);
        block.opacity = 1;
        PulsarConfig.features.trackTileEntityLight = false;
        for (int x=0;x<10;x++) manager.tileEntityChanged(new BlockPos(x,64,0));
        var changes = new java.util.ArrayList<Integer>();
        int before = block.calls;
        manager.flushSamples((x,y,z,lanes) -> changes.add(lanes));
        assertEquals(10, block.calls - before);
        assertEquals(10, changes.size());
        assertTrue(changes.stream().allMatch(lanes -> lanes == ContextualLightSnapshot.BOTH_CHANGED));
        assertFalse(manager.hasPending(0,0));
    }

    @Test void workerSnapshotMissesAreCorrectedEvenWithFallbackPollingDisabled() {
        pollingLimit(1);
        var block=new TileLightBlock(); var replacement=new TileLightBlock();
        var chunk=tileChunk(block,0,1);
        var manager=new ContextualLightManager(null,WorldHeightContext.VANILLA);
        manager.load(chunk);
        replacement.emission=7;
        chunk.getBlockStorageArray()[4].set(0,0,0,replacement.getDefaultState());
        PulsarConfig.features.trackTileEntityLight=false;
        manager.read(LightInfo.of(replacement.getDefaultState()),replacement.getDefaultState(),0,64,0);
        assertEquals(0,replacement.calls,"Worker-side reads must not invoke live callbacks");
        var lanes=new java.util.ArrayList<Integer>();
        manager.flushSamples((x,y,z,flags) -> lanes.add(flags));
        assertEquals(java.util.List.of(ContextualLightSnapshot.BOTH_CHANGED),lanes);
        assertEquals(1,replacement.calls);
        assertEquals(7,LightInfo.emission(manager.read(LightInfo.of(replacement.getDefaultState()),replacement.getDefaultState(),0,64,0)));
    }

    @Test void roundRobinContinuesAcrossChunksAndDiscardsUnloadedChunks() {
        pollingLimit(1);
        var first = new TileLightBlock(); var second = new TileLightBlock();
        var manager = new ContextualLightManager(null, WorldHeightContext.VANILLA);
        manager.load(tileChunk(first,0,3)); manager.load(tileChunk(second,1,3));
        int a=first.calls, b=second.calls;
        for (int tick=0;tick<6;tick++) manager.flushSamples((x,y,z,lanes) -> fail("Stationary sources changed"));
        assertEquals(3,first.calls-a); assertEquals(3,second.calls-b);
        manager.unload(0,0); a=first.calls; b=second.calls;
        for (int tick=0;tick<3;tick++) manager.flushSamples((x,y,z,lanes) -> fail("Stationary sources changed"));
        assertEquals(a,first.calls); assertEquals(3,second.calls-b);
    }

    @Test void denseChunksAndSparseChunksShareOneSourceCursor() {
        pollingLimit(3);
        var block=new TileLightBlock();
        var manager=new ContextualLightManager(null,WorldHeightContext.VANILLA);
        manager.load(tileChunk(block,0,40));
        for(int c=1;c<=3;c++)manager.load(tileChunk(block,c,1));
        block.visited=new java.util.HashSet<>();
        for(int tick=0;tick<15;tick++) {
            int before=block.calls;
            manager.flushSamples((x,y,z,lanes) -> fail("Stationary light changed"));
            assertEquals(3,block.calls-before);
        }
        assertEquals(43,block.visited.size(),"Dense chunks must not wait longer simply because other chunks contain few sources");
    }

    @Test void reentrantUnloadDoesNotResurrectTrackingOrPublishObsoleteLight() {
        pollingLimit(64);
        var block = new TileLightBlock();
        var manager = new ContextualLightManager(null, WorldHeightContext.VANILLA);
        manager.load(tileChunk(block,0,10));
        block.emission=15;
        block.callback=() -> manager.unload(0,0);
        int before=block.calls;
        manager.flushSamples((x,y,z,lanes) -> fail("Unloaded chunk published light"));
        assertEquals(1,block.calls-before);
        manager.flushSamples((x,y,z,lanes) -> fail("Unloaded chunk published light"));
        assertEquals(1,block.calls-before);
    }

    @Test void oneSlowModCallbackExhaustsTheSoftPollingBudget() {
        pollingLimit(64);
        PulsarConfig.performance.tileEntityLightBudgetMicros=1000;
        var block=new TileLightBlock();
        var manager=new ContextualLightManager(null,WorldHeightContext.VANILLA);
        manager.load(tileChunk(block,0,10));
        block.callback=() -> java.util.concurrent.locks.LockSupport.parkNanos(5_000_000L);
        int before=block.calls;
        manager.flushSamples((x,y,z,lanes) -> fail("Stationary sources changed"));
        assertTrue(block.calls-before<=1,"Polling must yield after the callback exceeds its time budget");
    }

    @Test void tileReplacementAndRemovalDoNotLeaveStaleTrackedObjects() {
        pollingLimit(1);
        var block=new TileLightBlock();
        var chunk=tileChunk(block,0,1);
        var manager=new ContextualLightManager(null,WorldHeightContext.VANILLA);
        manager.load(chunk);
        var replacement=new net.minecraft.tileentity.TileEntity() {};
        chunk.getTileEntityMap().put(new BlockPos(0,64,0),replacement);
        manager.flushSamples((x,y,z,lanes) -> fail("Identical replacement changed light"));
        replacement.invalidate();
        manager.flushSamples((x,y,z,lanes) -> fail("Invalid tile changed constant light"));
        int before=block.calls;
        manager.flushSamples((x,y,z,lanes) -> fail("Removed source published light"));
        assertEquals(before,block.calls,"The replacement, rather than the old tile, must be checked and removed");
    }

    @Test void aPolledChangeRefreshesContextualNeighboursWithoutTileEntities() {
        pollingLimit(1);
        var source=new TileLightBlock(); var neighbour=new TileLightBlock();
        var chunk=tileChunk(source,0,1);
        chunk.getBlockStorageArray()[4].set(1,0,0,neighbour.getDefaultState());
        var manager=new ContextualLightManager(null,WorldHeightContext.VANILLA);
        manager.load(chunk);
        source.emission=9; neighbour.opacity=1;
        var changes=new java.util.HashMap<BlockPos,Integer>();
        manager.flushSamples((x,y,z,lanes) -> changes.put(new BlockPos(x,y,z),lanes));
        assertEquals(ContextualLightSnapshot.BLOCK_CHANGED,changes.get(new BlockPos(0,64,0)));
        assertTrue(manager.hasPending(0,0));
        manager.flushSamples((x,y,z,lanes) -> changes.put(new BlockPos(x,y,z),lanes));
        assertEquals(ContextualLightSnapshot.BOTH_CHANGED,changes.get(new BlockPos(1,64,0)));
        assertEquals(1,LightInfo.opacity(manager.read(LightInfo.of(neighbour.getDefaultState()),neighbour.getDefaultState(),1,64,0)));
    }

    @Test void contextualNeighboursArePolledEvenWhenTheTilesOwnLightDoesNotChange() {
        pollingLimit(1);
        for(boolean tileChunkFirst:new boolean[]{true,false}) {
            var source=new TileLightBlock(); var neighbour=new TileLightBlock();
            var tileChunk=tileChunk(source,0,1);
            var adjacentChunk=new TestChunk(-1,0);
            var section=new ExtendedBlockStorage(64,true);
            section.set(15,0,0,neighbour.getDefaultState());
            adjacentChunk.getBlockStorageArray()[4]=section;
            var manager=new ContextualLightManager(null,WorldHeightContext.VANILLA);
            if(tileChunkFirst) { manager.load(tileChunk); manager.load(adjacentChunk); }
            else { manager.load(adjacentChunk); manager.load(tileChunk); }
            neighbour.opacity=1;
            var changed=new java.util.HashSet<BlockPos>();
            for(int tick=0;tick<2;tick++)manager.flushSamples((x,y,z,lanes) -> changed.add(new BlockPos(x,y,z)));
            assertEquals(java.util.Set.of(new BlockPos(-1,64,0)),changed);
            assertEquals(1,LightInfo.opacity(manager.read(LightInfo.of(neighbour.getDefaultState()),neighbour.getDefaultState(),-1,64,0)));
        }
    }

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
