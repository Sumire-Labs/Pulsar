package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.config.PulsarConfig;
import com.sumirelabs.pulsar.light.engine.LightInfo;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import com.sumirelabs.pulsar.world.PulsarWorld;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraftforge.fml.common.Loader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Issue #14: a world-aware light getter must not recreate multiblock tiles on a worker. */
class ContextualLightWorkerSafetyTest {
    private static final BlockPos POS = new BlockPos(1, 64, 1);

    @BeforeAll static void bootstrap() { Bootstrap.register(); }

    private static final class TileReadingBlock extends Block {
        final int emission;
        TileReadingBlock(int emission) { super(Material.ROCK); this.emission = emission; }
        @Override public int getLightValue(IBlockState state, IBlockAccess access, BlockPos pos) {
            // Same hazardous call as IE's BlockIETileProvider.getLightValue.
            access.getTileEntity(pos);
            return emission;
        }
        @Override public int getLightOpacity(IBlockState state, IBlockAccess access, BlockPos pos) {
            access.getTileEntity(pos);
            return 3;
        }
    }

    private static final class StorageChunk extends Chunk {
        StorageChunk() { super(null, 0, 0); }
        @Override public IBlockState getBlockState(int x, int y, int z) {
            ExtendedBlockStorage section = getBlockStorageArray()[y >> 4];
            return section == null ? Blocks.AIR.getDefaultState() : section.get(x & 15, y & 15, z & 15);
        }
    }

    private static final class TileWorld extends WorldServer implements PulsarWorld {
        Thread owner;
        StorageChunk chunk;
        WorldLightManager manager;
        int creations, lookups;

        // Never executed: the fixture allocates an inert server World and
        // manager without launching a game server or background engine pools.
        TileWorld() { super(null, null, null, 0, null); }
        @Override public TileEntity getTileEntity(BlockPos pos) {
            assertSame(owner, Thread.currentThread(), "Lighting worker accessed live TileEntities");
            lookups++;
            TileEntity tile = chunk.getTileEntityMap().get(pos);
            if (tile == null) {
                tile = new TileEntity() {};
                tile.setPos(pos.toImmutable());
                chunk.getTileEntityMap().put(pos.toImmutable(), tile);
                creations++;
            }
            return tile;
        }
        @Override public WorldLightManager pulsar$getLightManager() { return manager; }
        @Override public void pulsar$shutdown() {}
        @Override public Chunk pulsar$getAnyChunkImmediately(int x, int z) { return x == 0 && z == 0 ? chunk : null; }
        @Override public boolean pulsar$hasChunkPendingLight(int x, int z) { return false; }
        @Override public void pulsar$flushLightUpdates() {}
    }

    private static <T> T inert(Class<T> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        var singleton = unsafe.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(singleton.get(null), type));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void workersUseSnapshotsDuringFormationAndReplacementWithPollingEnabledOrDisabled(boolean polling) throws Exception {
        boolean previousPolling = PulsarConfig.features.trackTileEntityLight;
        var mods = Loader.class.getDeclaredField("namedMods");
        mods.setAccessible(true);
        Object previousMods = mods.get(Loader.instance());
        if (previousMods == null) mods.set(Loader.instance(), java.util.Map.of());
        try {
            PulsarConfig.features.trackTileEntityLight = polling;
            TileWorld world = inert(TileWorld.class);
            world.owner = Thread.currentThread();
            world.chunk = new StorageChunk();
            var section = new ExtendedBlockStorage(64, true);
            world.chunk.getBlockStorageArray()[4] = section;
            var original = new TileReadingBlock(12).getDefaultState();
            section.set(1, 0, 1, original);
            world.manager = inert(WorldLightManager.class);
            var samples = new ContextualLightManager(world, WorldHeightContext.VANILLA);
            var contextual = WorldLightManager.class.getDeclaredField("contextualLight");
            contextual.setAccessible(true);
            contextual.set(world.manager, samples);
            samples.load(world.chunk);
            assertEquals(1, world.creations);
            int initialLookups = world.lookups;
            try (var worker = Executors.newSingleThreadExecutor()) {
                int cached = worker.submit(() -> {
                    int value = 0;
                    for (int i = 0; i < 1000; i++) value = resolve(world, original);
                    return value;
                }).get(5, TimeUnit.SECONDS);
                assertEquals(12, LightInfo.emission(cached));
                assertEquals(3, LightInfo.opacity(cached));
                assertEquals(initialLookups, world.lookups);

                // A forming/replaced multiblock may temporarily have no tile.
                // A worker miss must not call getTileEntity and recreate it.
                world.chunk.getTileEntityMap().clear();
                var replacement = new TileReadingBlock(7).getDefaultState();
                section.set(1, 0, 1, replacement);
                int fallback = worker.submit(() -> resolve(world, replacement)).get(5, TimeUnit.SECONDS);
                assertEquals(LightInfo.of(replacement), fallback);
                assertEquals(initialLookups, world.lookups);
                assertEquals(1, world.creations);
                assertTrue(world.chunk.getTileEntityMap().isEmpty());
                assertTrue(samples.hasPending(0, 0));

                var corrections = new java.util.ArrayList<Integer>();
                samples.flushSamples((x, y, z, lanes) -> corrections.add(lanes));
                assertEquals(java.util.List.of(ContextualLightSnapshot.BOTH_CHANGED), corrections);
                assertEquals(2, world.creations, "Only the owner thread may recreate a missing tile");
                int afterPublication = world.lookups;
                int refreshed = worker.submit(() -> resolve(world, replacement)).get(5, TimeUnit.SECONDS);
                assertEquals(7, LightInfo.emission(refreshed));
                assertEquals(3, LightInfo.opacity(refreshed));
                assertEquals(afterPublication, world.lookups);
                assertEquals(2, world.creations);
            }
        } finally {
            PulsarConfig.features.trackTileEntityLight = previousPolling;
            mods.set(Loader.instance(), previousMods);
        }
    }

    private static int resolve(TileWorld world, IBlockState state) {
        return LightInfo.resolveContextual(LightInfo.of(state), state, world,
                new BlockPos.MutableBlockPos(), POS.getX(), POS.getY(), POS.getZ());
    }
}
