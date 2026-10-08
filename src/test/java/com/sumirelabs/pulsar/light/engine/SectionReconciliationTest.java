package com.sumirelabs.pulsar.light.engine;

import com.sumirelabs.pulsar.util.WorldHeightContext;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.init.Bootstrap;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class SectionReconciliationTest {
    @BeforeAll static void bootstrap() { Bootstrap.register(); }

    private static class CountingSection extends ExtendedBlockStorage {
        int checks;
        boolean empty = true;

        CountingSection(int y) { super(y << 4, false); }

        @Override public boolean isEmpty() { checks++; return empty; }
    }

    private static class TestEngine extends ScalarBlockEngine {
        TestEngine(World world, WorldHeightContext height) { super(world, height); }

        void seed(CountingSection lower, CountingSection upper) {
            setupEncodeOffset(7, 128, 7);
            setChunkSectionInCache(0, -1, 0, lower);
            setChunkSectionInCache(0, 70, 0, upper);
            setEmptinessMapCache(0, 0, new boolean[heightContext.getTotalSections()]);
        }
    }

    @Test
    void denseChangesCheckEachSectionOnceAndResetBetweenBatches() throws Exception {
        final WorldHeightContext height = WorldHeightContext.contiguous(-4, 80);
        final TestEngine engine = new TestEngine(uninitializedWorld(), height);
        final CountingSection lower = new CountingSection(-1);
        final CountingSection upper = new CountingSection(70);
        engine.seed(lower, upper);
        final Chunk chunk = new Chunk(null, 0, 0);
        final IntSet positions = new IntOpenHashSet();
        for (int section : new int[]{-1, 70}) {
            for (int local = 0; local < 4096; local++) positions.add((section << 12) | local);
        }
        positions.add(100 << 12); // Outside world bounds must not enter the cache.
        final Boolean[] queued = new Boolean[height.getTotalSections()];
        queued[height.getSectionIndex(-1)] = false; // Stale queued transition is corrected.

        final Method reconcile = PulsarEngine.class.getDeclaredMethod(
                "reconcileSectionChanges", Chunk.class, IntSet.class, Boolean[].class);
        reconcile.setAccessible(true);
        final Boolean[] result = (Boolean[]) reconcile.invoke(engine, chunk, positions, queued);
        assertEquals(Boolean.TRUE, result[height.getSectionIndex(-1)]);
        assertEquals(Boolean.TRUE, result[height.getSectionIndex(70)]);
        assertNull(result[height.getSectionIndex(0)]);
        assertEquals(1, lower.checks);
        assertEquals(1, upper.checks);

        lower.empty = false;
        final Boolean[] next = (Boolean[]) reconcile.invoke(engine, chunk, positions, queued);
        assertEquals(Boolean.FALSE, next[height.getSectionIndex(-1)]);
        assertEquals(2, lower.checks);
        assertEquals(2, upper.checks);
    }

    /** Only isRemote is read by this cache fixture; avoid constructing a live server. */
    private static World uninitializedWorld() throws Exception {
        final Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        final Field singleton = unsafeType.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return (World) unsafeType.getMethod("allocateInstance", Class.class)
                .invoke(singleton.get(null), WorldServer.class);
    }
}
