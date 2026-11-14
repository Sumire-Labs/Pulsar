/*
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.sumirelabs.pulsar.light.engine;

import org.junit.jupiter.api.Test;

import com.sumirelabs.pulsar.api.ExtendedWorld;
import com.sumirelabs.pulsar.light.SWMRNibbleArray;
import com.sumirelabs.pulsar.util.WorldHeightContext;

import net.minecraft.init.Bootstrap;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

import static org.junit.jupiter.api.Assertions.*;

class EngineCacheFailureTest {

    private static final class TestWorld extends WorldServer implements ExtendedWorld {

        Chunk chunk;

        TestWorld() {
            super(null, null, null, 0, null);
        }

        public Chunk pulsar$getAnyChunkImmediately(int x, int z) {
            return chunk != null && x == chunk.x && z == chunk.z ? chunk : null;
        }

        public boolean pulsar$hasChunkPendingLight(int x, int z) {
            return false;
        }

        public void pulsar$flushLightUpdates() {}

    }

    private static final class FailingEngine extends ScalarBlockEngine {

        final RuntimeException failure = new IllegalStateException("Injected nibble lookup failure");
        int cleanups;

        FailingEngine(TestWorld world) {
            super(world, WorldHeightContext.VANILLA);
        }

        @Override
        protected boolean canUseChunk(Chunk chunk) {
            return true;
        }

        @Override
        protected boolean[] getEmptinessMap(Chunk chunk) {
            return new boolean[16];
        }

        @Override
        protected SWMRNibbleArray[] getNibblesOnChunk(Chunk chunk) {
            assertSame(chunk, getChunkInCache(chunk.x, chunk.z));
            assertNotNull(getChunkSection(chunk.x, 0, chunk.z));
            throw failure;
        }

        @Override
        protected void onDestroyCaches() {
            cleanups++;
        }

    }

    @Test
    void failedSetupIsCleanedBeforeAnEngineIsReused() throws Exception {
        Bootstrap.register();
        var mods = net.minecraftforge.fml.common.Loader.class.getDeclaredField("namedMods");
        mods.setAccessible(true);
        if (mods.get(net.minecraftforge.fml.common.Loader.instance()) == null)
            mods.set(net.minecraftforge.fml.common.Loader.instance(), new java.util.HashMap<>());
        var unsafe = Class.forName("sun.misc.Unsafe");
        var field = unsafe.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        for (int path = 0; path < 5; path++) {
            TestWorld world = (TestWorld) unsafe.getMethod("allocateInstance", Class.class)
                .invoke(field.get(null), TestWorld.class);
            world.chunk = new Chunk(null, -1, -1);
            world.chunk.getBlockStorageArray()[0] = new ExtendedBlockStorage(0, false);
            FailingEngine engine = new FailingEngine(world);
            Chunk center = new Chunk(null, 0, 0);
            final int operation = path;
            assertSame(engine.failure, assertThrows(IllegalStateException.class, () -> {
                switch (operation) {
                    case 0 -> engine.blockChanged(0, 64, 0);
                    case 1 -> engine.blocksChangedInChunk(0, 0, new IntOpenHashSet(), null);
                    case 2 -> engine.light(center, null, false);
                    case 3 -> engine.loadInChunk(center, null);
                    default -> engine.checkChunkEdges(0, 0, new IntOpenHashSet());
                }
            }));
            assertEquals(1, engine.cleanups, "Cleanup hook path=" + path);
            for (Object[] cache : new Object[][]{engine.chunkCache, engine.sectionCache, engine.nibbleCache,
                engine.emptinessMapCache, engine.fluidCapCache})
                for (Object entry : cache)
                    assertNull(entry, "Retained cache entry path=" + path);
            world.chunk = null;
            assertDoesNotThrow(() -> engine.blockChanged(0, 64, 0));
            assertEquals(2, engine.cleanups);
        }
    }

}
