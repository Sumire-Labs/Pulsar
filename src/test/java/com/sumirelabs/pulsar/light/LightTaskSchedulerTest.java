package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.util.CoordinateUtils;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import org.junit.jupiter.api.Test;
import net.minecraft.world.chunk.Chunk;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class LightTaskSchedulerTest {
    @Test
    void blockedEditCandidatesAreCheckedOncePerClaimAndBecomeAvailableOnTheNextClaim() {
        final LightQueue queue = new LightQueue(WorldHeightContext.VANILLA);
        final LightTaskScheduler scheduler = new LightTaskScheduler(queue, () -> 0L);
        for (int x = 0; x < 100; x++) queue.queueBlockChange(x << 4, 64, 0);
        final AtomicInteger checks = new AtomicInteger();
        assertNull(scheduler.claimAvailable(key -> { checks.incrementAndGet(); return false; }));
        assertEquals(100, checks.get());
        final ChunkTasks task = scheduler.claimAvailable(key -> true);
        assertNotNull(task);
        queue.completeTask(task);
        assertEquals(99, queue.size());
    }

    @Test
    void parallelClaimsSkipBlockedCentersAndKeepQueuedAndInFlightFuturesDistinct() {
        final LightQueue queue = new LightQueue(WorldHeightContext.VANILLA);
        final LightTaskScheduler scheduler = new LightTaskScheduler(queue, () -> 0L);
        queue.queueBlockChange(0, 64, 0);
        queue.queueBlockChange(16 * 6, 64, 0);
        final long blocked = CoordinateUtils.getChunkKey(0, 0);
        final var waiting = queue.getPendingWorkFuture(blocked);
        final ChunkTasks independent = scheduler.claimAvailable(key -> key != blocked);
        assertEquals(CoordinateUtils.getChunkKey(6, 0), independent.chunkCoordinate);
        assertFalse(waiting.isDone());
        assertNull(scheduler.claimAvailable(key -> key != blocked));
        final ChunkTasks first = scheduler.claimAvailable(key -> true);
        assertEquals(blocked, first.chunkCoordinate);
        queue.queueBlockChange(0, 65, 0);
        // Do not replace inFlightTasks[key] with a subsequent generation.
        assertNull(queue.removeFirstTask());
        assertNull(scheduler.claimAvailable(key -> true));
        assertSame(first.onComplete, queue.getPendingWorkFuture(blocked));
        queue.completeTask(first);
        assertTrue(waiting.isDone());
        final ChunkTasks next = scheduler.claimAvailable(key -> true);
        assertNotNull(next);
        assertFalse(next.onComplete.isDone());
        queue.completeTask(next);
        queue.completeTask(independent);
        assertFalse(queue.hasWork());
    }

    @Test
    void singleTaskTurnsAllowBothLanesToProgressWithinASharedBudget() {
        final LightQueue sky = new LightQueue(WorldHeightContext.VANILLA);
        final LightQueue block = new LightQueue(WorldHeightContext.VANILLA);
        final AtomicLong clock = new AtomicLong();
        final LightTaskScheduler skyScheduler = new LightTaskScheduler(sky, clock::get);
        final LightTaskScheduler blockScheduler = new LightTaskScheduler(block, clock::get);
        for (int x = 0; x < 4; x++) {
            sky.queueBlockChange(x << 4, 64, 0);
            block.queueBlockChange(x << 4, 64, 0);
        }
        final java.util.List<String> order = new java.util.ArrayList<>();
        while (clock.get() < 4) {
            skyScheduler.drainOneUntil(4, () -> true, task -> {
                order.add("sky");
                clock.incrementAndGet();
            });
            blockScheduler.drainOneUntil(4, () -> true, task -> {
                order.add("block");
                clock.incrementAndGet();
            });
        }
        assertEquals(java.util.List.of("sky", "block", "sky", "block"), order);
        assertEquals(2, sky.size());
        assertEquals(2, block.size());
        assertFalse(blockScheduler.drainOneUntil(4, () -> true, task -> fail("Budget exhausted")));
    }

    @Test
    void singleTaskOvershootLeavesOtherLaneQueuedAndCompletesTheCurrentFuture() {
        final LightQueue sky = new LightQueue(WorldHeightContext.VANILLA);
        final LightQueue block = new LightQueue(WorldHeightContext.VANILLA);
        final AtomicLong clock = new AtomicLong();
        sky.queueBlockChange(0, 64, 0);
        block.queueBlockChange(0, 64, 0);
        final var skyFuture = sky.getPendingWorkFuture(CoordinateUtils.getChunkKey(0, 0));
        assertTrue(new LightTaskScheduler(sky, clock::get).drainOneUntil(2, () -> true,
                task -> clock.set(12)));
        assertTrue(skyFuture.isDone());
        assertFalse(new LightTaskScheduler(block, clock::get).drainOneUntil(2, () -> true,
                task -> fail("Must not start after deadline")));
        assertTrue(block.hasPendingWork(0, 0));
    }

    @Test
    void generationCannotStarveItsOwnFinalEdgePass() {
        final LightQueue queue = new LightQueue(WorldHeightContext.VANILLA);
        final AtomicLong clock = new AtomicLong();
        final LightTaskScheduler scheduler = new LightTaskScheduler(queue, clock::get);
        final Chunk chunk = new Chunk(null, 0, 0);
        queue.queueChunkLight(0, 0, chunk, new Boolean[16], 1L);
        final AtomicInteger initial = new AtomicInteger();
        final AtomicInteger edges = new AtomicInteger();
        for (int tick = 0; tick < 2; tick++) {
            scheduler.drainUntil(clock.get() + 1, () -> true, task -> {
                clock.incrementAndGet();
                if (task.initialLightChunk != null) {
                    initial.incrementAndGet();
                    queue.queueInitialLightEdgeCheckAllSections(0, 0, true, 1L, 0);
                    queue.queueChunkLight(1, 0, chunk, new Boolean[16], 2L);
                } else {
                    assertEquals(1L, task.initialLightEdgeGeneration);
                    edges.incrementAndGet();
                }
            });
        }
        assertEquals(1, initial.get());
        assertEquals(1, edges.get());
        assertTrue(queue.hasInitialLightTask());
    }

    @Test
    void maintenanceRunsDespiteAContinuouslyReplenishedEditQueueAcrossBudgetYields() {
        final LightQueue queue = new LightQueue(WorldHeightContext.VANILLA);
        final AtomicLong clock = new AtomicLong();
        final LightTaskScheduler scheduler = new LightTaskScheduler(queue, clock::get);
        queue.queueBlockChange(0, 64, 0);
        queue.queueInitialLightEdgeCheckAllSections(9, 0, true, 1L, 0);
        final long edgeKey = CoordinateUtils.getChunkKey(9, 0);
        final var completion = queue.getPendingWorkFuture(edgeKey);
        final AtomicInteger edits = new AtomicInteger();
        for (int tick = 0; tick < 5; tick++) {
            scheduler.drainUntil(clock.get() + 1, () -> true, task -> {
                clock.incrementAndGet();
                if (task.changedPositions != null) {
                    edits.incrementAndGet();
                    queue.queueBlockChange(0, 64, 0);
                }
            });
        }
        assertEquals(4, edits.get());
        assertTrue(completion.isDone());
        assertTrue(queue.hasWork());
    }

    @Test
    void sharedDeadlineLeavesTheSecondLanesWorkQueuedAfterAnExpensiveTask() {
        final LightQueue sky = new LightQueue(WorldHeightContext.VANILLA);
        final LightQueue block = new LightQueue(WorldHeightContext.VANILLA);
        final AtomicLong clock = new AtomicLong();
        sky.queueBlockChange(0, 64, 0);
        sky.queueBlockChange(16, 64, 0);
        block.queueBlockChange(0, 64, 0);
        final LightTaskScheduler skyScheduler = new LightTaskScheduler(sky, clock::get);
        final LightTaskScheduler blockScheduler = new LightTaskScheduler(block, clock::get);
        assertTrue(skyScheduler.drainUntil(5, () -> true, task -> clock.set(12)));
        assertEquals(1, sky.size());
        assertFalse(sky.hasPendingWork(0, 0));
        assertTrue(blockScheduler.drainUntil(5, () -> true, task -> fail("Deadline expired")));
        assertEquals(1, block.size());
        assertFalse(blockScheduler.drainUntil(20, () -> true, task -> clock.incrementAndGet()));
        assertFalse(block.hasWork());
    }

    @Test
    void promotedMaintenanceAndRemovedBatchesCannotBeProcessedThroughStaleEntries() {
        final LightQueue queue = new LightQueue(WorldHeightContext.VANILLA);
        queue.queueEdgeCheck(0, 0, 4, true);
        queue.queueBlockChange(1, 64, 1);
        queue.queueEdgeCheck(2, 0, 4, true);
        queue.removeChunk(2, 0);
        queue.queueEdgeCheck(2, 0, 5, true);
        final ChunkTasks replacement = queue.removeFirstMaintenanceTask();
        assertEquals(CoordinateUtils.getChunkKey(2, 0), replacement.chunkCoordinate);
        assertTrue(replacement.queuedEdgeChecksSky.contains(5));
        assertFalse(replacement.queuedEdgeChecksSky.contains(4));
        queue.completeTask(replacement);
        assertNull(queue.removeFirstMaintenanceTask());
        final ChunkTasks edit = queue.removeFirstBlockChangeTask();
        assertNotNull(edit);
        queue.completeTask(edit);
        assertFalse(queue.hasWork());
    }

    @Test
    void exceptionCompletesTheInFlightTaskAndLeavesOtherTasksQueued() {
        final LightQueue queue = new LightQueue(WorldHeightContext.VANILLA);
        queue.queueBlockChange(0, 64, 0);
        queue.queueBlockChange(16, 64, 0);
        final LightTaskScheduler scheduler = new LightTaskScheduler(queue, () -> 0);
        assertThrows(IllegalStateException.class, () -> scheduler.drainUntil(5, () -> true,
                task -> { throw new IllegalStateException("probe"); }));
        assertFalse(queue.hasPendingWork(0, 0));
        assertEquals(1, queue.size());
    }
}
