package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.util.WorldHeightContext;
import com.sumirelabs.pulsar.util.CoordinateUtils;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ParallelLightSchedulerTest {
    @Test
    void pairedLaneRemainsQueuedAndAbsorbsChangesUntilTheFirstLaneFinishes() throws Exception {
        Object world = new Object();
        LightQueue sky = new LightQueue(WorldHeightContext.VANILLA), block = new LightQueue(WorldHeightContext.VANILLA);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger blockCalls = new AtomicInteger();
        AtomicInteger positions = new AtomicInteger();
        LightEngineWorker first = new LightEngineWorker(sky, () -> null, (task, engine) -> {
            started.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, new AtomicInteger(), "test", "unused", false, world, new AtomicInteger());
        LightEngineWorker second = new LightEngineWorker(block, () -> null, (task, engine) -> {
            positions.set(task.changedPositions.size()); blockCalls.incrementAndGet();
        },
                new AtomicInteger(), "test", "unused", false, world, new AtomicInteger());
        ParallelLightScheduler pool = ParallelLightScheduler.shared(4);
        sky.queueBlockChange(0, 64, 0);
        block.queueBlockChange(0, 64, 0);
        var blockDone = block.getPendingWorkFuture(0L);
        pool.pair(first, second);
        pool.register(first);
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertFalse(blockDone.isDone());
            assertFalse(block.isEmpty(), "The reservation must protect the still-queued partner");
            block.queueBlockChange(1, 64, 0);
            assertSame(blockDone, block.getPendingWorkFuture(0L));
            release.countDown();
            blockDone.get(5, TimeUnit.SECONDS);
            assertEquals(1, blockCalls.get());
            assertEquals(2, positions.get());
        } finally {
            release.countDown(); pool.unregister(first);
            first.requestStop(); second.requestStop(); first.awaitStop(); second.awaitStop();
        }
    }

    @Test
    void partnerArrivingDuringFirstLaneRunsOnTheSameWorkerUnderTheReservation() throws Exception {
        Object world = new Object();
        LightQueue sky = new LightQueue(WorldHeightContext.VANILLA), block = new LightQueue(WorldHeightContext.VANILLA);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), blockStarted = new CountDownLatch(1);
        AtomicReference<Thread> skyThread = new AtomicReference<>(), blockThread = new AtomicReference<>();
        LightEngineWorker first = new LightEngineWorker(sky, () -> null, (task, engine) -> {
            skyThread.set(Thread.currentThread()); started.countDown(); waitFor(release);
        }, new AtomicInteger(), "test", "unused", false, world, new AtomicInteger());
        LightEngineWorker second = new LightEngineWorker(block, () -> null, (task, engine) -> {
            blockThread.set(Thread.currentThread()); blockStarted.countDown();
        }, new AtomicInteger(), "test", "unused", false, world, new AtomicInteger());
        ParallelLightScheduler pool = ParallelLightScheduler.shared(4);
        pool.pair(first, second); pool.register(first); pool.register(second);
        try {
            sky.queueBlockChange(0, 64, 0);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            block.queueBlockChange(0, 64, 0);
            var done = block.getPendingWorkFuture(0L);
            assertFalse(blockStarted.await(100, TimeUnit.MILLISECONDS));
            release.countDown(); done.get(5, TimeUnit.SECONDS);
            assertSame(skyThread.get(), blockThread.get());
        } finally {
            release.countDown(); pool.unregister(first); pool.unregister(second);
            first.requestStop(); second.requestStop(); first.awaitStop(); second.awaitStop();
        }
    }

    @Test
    void changesAfterPartnerStartsRetainTheirOwnBatchAndCompletionFuture() throws Exception {
        Object world = new Object();
        LightQueue sky = new LightQueue(WorldHeightContext.VANILLA), block = new LightQueue(WorldHeightContext.VANILLA);
        CountDownLatch firstStarted = new CountDownLatch(1), secondStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1), releaseSecond = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger(), firstSize = new AtomicInteger(), secondSize = new AtomicInteger();
        LightEngineWorker first = new LightEngineWorker(sky, () -> null, (task, engine) -> {},
                new AtomicInteger(), "test", "unused", false, world, new AtomicInteger());
        LightEngineWorker second = new LightEngineWorker(block, () -> null, (task, engine) -> {
            if (calls.incrementAndGet() == 1) {
                firstSize.set(task.changedPositions.size()); firstStarted.countDown(); waitFor(releaseFirst);
            } else {
                secondSize.set(task.changedPositions.size()); secondStarted.countDown(); waitFor(releaseSecond);
            }
        }, new AtomicInteger(), "test", "unused", false, world, new AtomicInteger());
        ParallelLightScheduler pool = ParallelLightScheduler.shared(4);
        sky.queueBlockChange(0, 64, 0); block.queueBlockChange(0, 64, 0);
        var firstDone = block.getPendingWorkFuture(0L);
        pool.pair(first, second); pool.register(first);
        try {
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            pool.register(second); block.queueBlockChange(1, 64, 0);
            releaseFirst.countDown();
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            firstDone.get(5, TimeUnit.SECONDS);
            var secondDone = block.getPendingWorkFuture(0L);
            assertNotSame(firstDone, secondDone); assertFalse(secondDone.isDone());
            releaseSecond.countDown(); secondDone.get(5, TimeUnit.SECONDS);
            assertEquals(2, calls.get()); assertEquals(1, firstSize.get()); assertEquals(1, secondSize.get());
        } finally {
            releaseFirst.countDown(); releaseSecond.countDown(); pool.unregister(first); pool.unregister(second);
            first.requestStop(); second.requestStop(); first.awaitStop(); second.awaitStop();
        }
    }

    @Test
    void stoppedPartnerIsNotClaimedAfterFirstLaneCompletes() throws Exception {
        Object world = new Object();
        LightQueue sky = new LightQueue(WorldHeightContext.VANILLA), block = new LightQueue(WorldHeightContext.VANILLA);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger blockCalls = new AtomicInteger();
        LightEngineWorker first = new LightEngineWorker(sky, () -> null, (task, engine) -> {
            started.countDown(); waitFor(release);
        }, new AtomicInteger(), "test", "unused", false, world, new AtomicInteger());
        LightEngineWorker second = new LightEngineWorker(block, () -> null, (task, engine) -> blockCalls.incrementAndGet(),
                new AtomicInteger(), "test", "unused", false, world, new AtomicInteger());
        ParallelLightScheduler pool = ParallelLightScheduler.shared(4);
        sky.queueBlockChange(0, 64, 0); block.queueBlockChange(0, 64, 0);
        var skyDone = sky.getPendingWorkFuture(0L); var blockDone = block.getPendingWorkFuture(0L);
        pool.pair(first, second); pool.register(first);
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS)); second.requestStop();
            release.countDown(); skyDone.get(5, TimeUnit.SECONDS);
            pool.unregister(first); first.requestStop(); first.awaitStop(); second.awaitStop();
            assertEquals(0, blockCalls.get()); assertFalse(blockDone.isDone());
            block.removeChunk(0, 0); assertTrue(blockDone.isDone());
        } finally {
            release.countDown(); pool.unregister(first);
            first.requestStop(); second.requestStop(); first.awaitStop(); second.awaitStop();
            block.removeChunk(0, 0);
        }
    }

    private static void waitFor(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Test task was not released");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new AssertionError(interrupted);
        }
    }
    @Test
    void runsFourIndependentJobsConcurrentlyAndCompletesAllQueuedFutures() throws Exception {
        final LightQueue queue = new LightQueue(WorldHeightContext.VANILLA);
        final CountDownLatch started = new CountDownLatch(4), release = new CountDownLatch(1);
        final AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
        final LightEngineWorker lane = new LightEngineWorker(queue, () -> null, (task, engine) -> {
            int jobs = active.incrementAndGet();
            peak.accumulateAndGet(jobs, Math::max);
            started.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { active.decrementAndGet(); }
        }, new AtomicInteger(), "unit-test", "unused", false, new Object(), new AtomicInteger());
        final ParallelLightScheduler pool = ParallelLightScheduler.shared(4);
        final java.util.List<java.util.concurrent.Future<Void>> completions = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            queue.queueBlockChange(i * 8 * 16, 64, 0);
            completions.add(queue.getPendingWorkFuture(CoordinateUtils.getChunkKey(i * 8, 0)));
        }
        pool.register(lane);
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS), "Independent jobs must fill the bounded pool");
            assertEquals(4, peak.get());
            release.countDown();
            for (var future : completions) future.get(5, TimeUnit.SECONDS);
            assertFalse(queue.hasWork());
        } finally {
            release.countDown();
            pool.unregister(lane);
            lane.requestStop();
            lane.awaitStop();
        }
    }

    @Test
    void overlappingJobsWaitForReservationReleaseAndQueuedWorkWakesIdleThreads() throws Exception {
        final LightQueue queue = new LightQueue(WorldHeightContext.VANILLA);
        final CountDownLatch first = new CountDownLatch(1), second = new CountDownLatch(1), release = new CountDownLatch(1);
        final LightEngineWorker lane = new LightEngineWorker(queue, () -> null, (task, engine) -> {
            if (task.chunkCoordinate == 0L) {
                first.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            } else second.countDown();
        }, new AtomicInteger(), "unit-test", "unused", false, new Object(), new AtomicInteger());
        final ParallelLightScheduler pool = ParallelLightScheduler.shared(4);
        pool.register(lane);
        try {
            queue.queueBlockChange(0, 64, 0);
            assertTrue(first.await(5, TimeUnit.SECONDS));
            queue.queueBlockChange(16, 64, 0);
            final var completed = queue.getPendingWorkFuture(CoordinateUtils.getChunkKey(1, 0));
            assertFalse(second.await(100, TimeUnit.MILLISECONDS), "Overlapping 5x5 jobs must not run together");
            assertFalse(completed.isDone());
            release.countDown();
            assertTrue(second.await(5, TimeUnit.SECONDS));
            completed.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.unregister(lane);
            lane.requestStop();
            lane.awaitStop();
        }
    }
}
