package com.sumirelabs.pulsar.light.engine;

import com.sumirelabs.pulsar.api.ExtendedWorld;
import com.sumirelabs.pulsar.light.SWMRNibbleArray;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import net.minecraft.init.Bootstrap;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ChunkPublicationTest {
    @BeforeAll static void bootstrap() { Bootstrap.register(); }

    private static class TestWorld extends WorldServer implements ExtendedWorld {
        Map<Integer, Chunk> loaded;
        int lookups;
        private TestWorld() { super(null, null, null, 0, null); }
        @Override public Chunk pulsar$getAnyChunkImmediately(int x, int z) { lookups++; return loaded.get(x); }
        @Override public boolean pulsar$hasChunkPendingLight(int x, int z) { return false; }
        @Override public void pulsar$flushLightUpdates() {}
    }

    private static class Engine extends ScalarBlockEngine {
        final ArrayList<Integer> published = new ArrayList<>();
        Runnable firstPublish;
        Engine(TestWorld world) { super(world, WorldHeightContext.contiguous(-4, 80)); }
        void chunk(int index, Chunk chunk) { chunkCache[index] = chunk; }
        SWMRNibbleArray dirty(int index) {
            SWMRNibbleArray n = new SWMRNibbleArray(); n.set(0, 7); nibbleCache[index] = n; return n;
        }
        void flush() { updateVisible(); }
        @Override protected void onNibbleVisible(int index, SWMRNibbleArray nibble) {
            if (!isClientSide) assertTrue(Thread.holdsLock(chunkCache[index % 25]));
            published.add(index);
            if (published.size() == 1 && firstPublish != null) firstPublish.run();
        }
    }

    private static TestWorld world(boolean client) throws Exception {
        Class<?> type = Class.forName("sun.misc.Unsafe");
        var field = type.getDeclaredField("theUnsafe"); field.setAccessible(true);
        TestWorld world = (TestWorld) type.getMethod("allocateInstance", Class.class).invoke(field.get(null), TestWorld.class);
        world.loaded = new HashMap<>();
        var remote = World.class.getDeclaredField("isRemote"); remote.setAccessible(true); remote.setBoolean(world, client);
        return world;
    }

    @Test void multipleDirtySectionsShareOneLiveChunkCheckAcrossExtendedHeight() throws Exception {
        TestWorld world = world(false); Engine engine = new Engine(world);
        Chunk chunk = new Chunk(null, 0, 0); world.loaded.put(0, chunk); engine.chunk(12, chunk);
        SWMRNibbleArray low = engine.dirty(12), high = engine.dirty(12 + 25 * 80);
        engine.flush();
        assertEquals(java.util.List.of(12, 12 + 25 * 80), engine.published);
        assertEquals(1, world.lookups);
        assertEquals(7, low.getVisible(0)); assertEquals(7, high.getVisible(0));
        engine.flush(); assertEquals(1, world.lookups); // No publication work remains.
    }

    @Test void detachedOrReplacedChunkCannotPublishAnyOfItsSections() throws Exception {
        TestWorld world = world(false); Engine engine = new Engine(world);
        Chunk old = new Chunk(null, 0, 0); engine.chunk(12, old);
        SWMRNibbleArray first = engine.dirty(12), last = engine.dirty(37);
        engine.flush(); assertTrue(engine.published.isEmpty());
        world.loaded.put(0, new Chunk(null, 0, 0));
        engine.flush(); assertTrue(engine.published.isEmpty());
        assertEquals(0, first.getVisible(0)); assertEquals(0, last.getVisible(0));
    }

    @Test void unloadCannotDetachAChunkHalfwayThroughItsPublication() throws Exception {
        TestWorld world = world(false); Engine engine = new Engine(world);
        Chunk chunk = new Chunk(null, 0, 0); world.loaded.put(0, chunk); engine.chunk(12, chunk);
        engine.dirty(12); engine.dirty(37);
        CountDownLatch entered = new CountDownLatch(1), attempting = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread unload = new Thread(() -> {
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS)); attempting.countDown();
                synchronized (chunk) { world.loaded.remove(0); }
            } catch (Throwable t) { failure.set(t); }
        });
        engine.firstPublish = () -> {
            entered.countDown();
            try { assertTrue(attempting.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new AssertionError(e); }
            assertSame(chunk, world.loaded.get(0));
        };
        unload.start(); engine.flush(); unload.join(5000);
        assertFalse(unload.isAlive()); assertNull(failure.get());
        assertEquals(java.util.List.of(12, 37), engine.published); assertNull(world.loaded.get(0));
    }

    @Test void clientRetainsOriginalSectionOrderWithoutServerRegistrationChecks() throws Exception {
        TestWorld world = world(true); Engine engine = new Engine(world);
        engine.dirty(13); engine.dirty(12); engine.dirty(37);
        engine.flush();
        assertEquals(java.util.List.of(12, 13, 37), engine.published); assertEquals(0, world.lookups);
    }
}
