package com.sumirelabs.pulsar.compat;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldThreadPacketGateTest {
    @Test void networkThreadNeverLoadsChunksAndOriginalHandlerRunsOnceOnWorldThread() throws Exception {
        final Thread world = Thread.currentThread();
        final var tasks = new ConcurrentLinkedQueue<Runnable>();
        final var loads = new AtomicInteger();
        final Runnable handler = () -> {
            assertSame(world, Thread.currentThread(), "Chunk/TE callbacks require the world thread");
            loads.incrementAndGet();
        };
        try (var worker = Executors.newSingleThreadExecutor()) {
            assertTrue(worker.submit(() -> WorldThreadPacketGate.defer(
                    () -> Thread.currentThread() == world, tasks::add, () -> true, handler))
                    .get(5, TimeUnit.SECONDS));
        }
        assertEquals(0, loads.get());
        assertEquals(1, tasks.size());
        tasks.remove().run();
        assertEquals(1, loads.get());
        assertTrue(tasks.isEmpty());
    }

    @Test void worldThreadReentryDoesNotRescheduleAndPreservesTheOriginalBody() {
        final var calls = new AtomicInteger();
        final var tasks = new ConcurrentLinkedQueue<Runnable>();
        assertFalse(WorldThreadPacketGate.defer(() -> true, tasks::add,
                () -> { fail("Validity is checked only for delayed packets"); return false; }, calls::incrementAndGet));
        // The injected gate falls through; the original method owns its checks and return value.
        calls.incrementAndGet();
        assertEquals(1, calls.get());
        assertTrue(tasks.isEmpty());
    }

    @Test void disconnectOrWorldChangeBeforeExecutionDropsTheLatePacket() {
        final var active = new AtomicBoolean(true);
        final var calls = new AtomicInteger();
        final var tasks = new ConcurrentLinkedQueue<Runnable>();
        assertTrue(WorldThreadPacketGate.defer(() -> false, tasks::add, active::get, calls::incrementAndGet));
        active.set(false);
        tasks.remove().run();
        assertEquals(0, calls.get());
    }

    @Test void validityIsReadOnTheWorldThreadAndPacketsAreNotCoalesced() throws Exception {
        final Thread world = Thread.currentThread();
        final var tasks = new ConcurrentLinkedQueue<Runnable>();
        final var calls = new AtomicInteger();
        try (var worker = Executors.newSingleThreadExecutor()) {
            for (int packet = 0; packet < 2; packet++) {
                worker.submit(() -> assertTrue(WorldThreadPacketGate.defer(
                        () -> Thread.currentThread() == world, tasks::add,
                        () -> { assertSame(world, Thread.currentThread()); return true; }, calls::incrementAndGet)))
                        .get(5, TimeUnit.SECONDS);
            }
        }
        assertEquals(0, calls.get());
        assertEquals(2, tasks.size());
        tasks.remove().run(); tasks.remove().run();
        assertEquals(2, calls.get());
    }
}
