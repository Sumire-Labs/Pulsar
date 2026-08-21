package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.util.CoordinateUtils;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class LightQueueTest {

    @Test
    void coalescesWakeSignalsWhileLaneIsActive() {
        final LightQueue queue = new LightQueue(WorldHeightContext.VANILLA);

        queue.queueBlockChange(1, 64, 1);
        queue.queueBlockChange(2, 64, 2);
        queue.queueBlockChange(2, 64, 2);
        queue.queueEdgeCheck(0, 0, 0, true);
        queue.queueBlockChange(32, 64, 0);

        assertEquals(1, queue.clearWorkSignal());
        assertEquals(0, queue.clearWorkSignal());
        assertEquals(2, queue.size());
    }

    @Test
    void inFlightTaskKeepsLaneActiveWithoutAnotherSignal() {
        final LightQueue queue = new LightQueue(WorldHeightContext.VANILLA);
        queue.queueBlockChange(1, 64, 1);
        assertEquals(1, queue.clearWorkSignal());

        final ChunkTasks first = queue.removeFirstBlockChangeTask();
        assertNotNull(first);
        assertTrue(queue.isEmpty());
        assertTrue(queue.hasWork());

        queue.queueBlockChange(17, 64, 1);

        assertFalse(queue.isEmpty());
        assertTrue(queue.hasWork());
        assertEquals(0, queue.clearWorkSignal());

        queue.completeTask(first);
        final ChunkTasks second = queue.removeFirstBlockChangeTask();
        assertNotNull(second);
        queue.completeTask(second);
        assertFalse(queue.hasWork());
    }

    @Test
    void idleLaneSignalsWorkQueuedAfterWorkerEmptyCheck() {
        final LightQueue queue = new LightQueue(WorldHeightContext.VANILLA);
        queue.queueBlockChange(1, 64, 1);
        assertTimeoutPreemptively(Duration.ofSeconds(5), queue::waitForWork);

        final ChunkTasks first = queue.removeFirstBlockChangeTask();
        assertNotNull(first);
        queue.completeTask(first);
        assertTrue(queue.isEmpty());
        assertFalse(queue.hasWork());

        // Models an enqueue between the worker's empty check and acquire.
        queue.queueBlockChange(17, 64, 1);
        assertTimeoutPreemptively(Duration.ofSeconds(5), queue::waitForWork);
        assertEquals(0, queue.clearWorkSignal());

        final ChunkTasks second = queue.removeFirstBlockChangeTask();
        assertNotNull(second);
        queue.completeTask(second);
    }

    @Test
    void removedLastTaskDoesNotSuppressNextIdleTransition() {
        final LightQueue queue = new LightQueue(WorldHeightContext.VANILLA);
        final long firstKey = CoordinateUtils.getChunkKey(0, 0);
        queue.queueBlockChange(1, 64, 1);
        final Future<Void> firstCompletion = queue.getPendingWorkFuture(firstKey);
        assertNotNull(firstCompletion);

        queue.removeChunk(0, 0);

        assertTrue(firstCompletion.isDone());
        assertFalse(queue.hasWork());

        queue.queueBlockChange(17, 64, 1);

        // One stale permit remains for the removed task, and the new idle
        // transition must still issue its own permit.
        assertEquals(2, queue.clearWorkSignal());
        final ChunkTasks next = queue.removeFirstBlockChangeTask();
        assertNotNull(next);
        queue.completeTask(next);
        assertFalse(queue.hasWork());
    }

    @Test
    void explicitWakeRemainsUnconditionalWhenLaneIsIdle() {
        final LightQueue queue = new LightQueue(WorldHeightContext.VANILLA);
        assertTrue(queue.isEmpty());
        assertFalse(queue.hasWork());

        queue.wakeUp();

        assertEquals(1, queue.clearWorkSignal());
    }

    @Test
    void rejectedGenerationDoesNotAddWakeSignal() {
        final LightQueue queue = new LightQueue(WorldHeightContext.VANILLA);
        assertTrue(queue.queueInitialLightEdgeCheckAllSections(0, 0, true, 9L, 0));
        assertEquals(1, queue.clearWorkSignal());

        assertFalse(queue.queueInitialLightEdgeCheckAllSections(0, 0, true, 8L, 1));

        assertEquals(0, queue.clearWorkSignal());
    }

    @Test
    void dequeuedValueTaskRemainsPendingUntilWorkerCompletesIt() {
        final LightQueue queue = new LightQueue(WorldHeightContext.VANILLA);
        final long key = CoordinateUtils.getChunkKey(2, -3);
        queue.queueBlockChange(32, 64, -48);

        final ChunkTasks task = queue.removeFirstBlockChangeTask();
        final Future<Void> completion = queue.getPendingWorkFuture(key);

        assertNotNull(task);
        assertNotNull(completion);
        assertTrue(queue.hasWork());
        assertTrue(queue.hasPendingWork(2, -3));
        assertTrue(queue.hasPendingLightWork(key));
        assertSame(completion, queue.getPendingWorkFuture(key));
        assertFalse(completion.isDone());

        queue.completeTask(task);

        assertFalse(queue.hasPendingWork(2, -3));
        assertFalse(queue.hasPendingLightWork(key));
        assertFalse(queue.hasWork());
        assertTrue(completion.isDone());
    }

    @Test
    void completingInFlightBatchDoesNotHideNewBatchForSameChunk() {
        final LightQueue queue = new LightQueue(WorldHeightContext.VANILLA);
        final long key = CoordinateUtils.getChunkKey(0, 0);
        queue.queueBlockChange(1, 32, 1);
        final ChunkTasks first = queue.removeFirstBlockChangeTask();
        final Future<Void> firstCompletion = queue.getPendingWorkFuture(key);

        queue.queueBlockChange(2, 32, 2);
        queue.completeTask(first);
        final Future<Void> secondCompletion = queue.getPendingWorkFuture(key);

        assertNotNull(firstCompletion);
        assertNotNull(secondCompletion);
        assertTrue(queue.hasPendingLightWork(key));
        assertNotSame(firstCompletion, secondCompletion);
        assertSame(secondCompletion, queue.getPendingWorkFuture(key));

        final ChunkTasks second = queue.removeFirstBlockChangeTask();
        assertNotNull(second);
        queue.completeTask(second);
        assertFalse(queue.hasPendingLightWork(key));
    }

    @Test
    void edgeOnlyTaskIsInFlightButDoesNotInvalidateSavedLight() {
        final LightQueue queue = new LightQueue(WorldHeightContext.VANILLA);
        final long key = CoordinateUtils.getChunkKey(4, 5);
        queue.queueEdgeCheck(4, 5, 0, true);

        final ChunkTasks task = queue.removeFirstTask();

        assertNotNull(task);
        assertTrue(queue.hasPendingWork(4, 5));
        assertFalse(queue.hasPendingLightWork(key));

        queue.completeTask(task);
        assertFalse(queue.hasPendingWork(4, 5));
    }

    @Test
    void finalEdgeTaskKeepsNewestGenerationAndRetryAttempt() {
        final LightQueue queue = new LightQueue(WorldHeightContext.VANILLA);

        assertTrue(queue.queueInitialLightEdgeCheckAllSections(7, -2, true, 8L, 0));
        assertTrue(queue.queueInitialLightEdgeCheckAllSections(7, -2, true, 8L, 1));
        assertFalse(queue.queueInitialLightEdgeCheckAllSections(7, -2, true, 7L, 2));
        assertTrue(queue.queueInitialLightEdgeCheckAllSections(7, -2, true, 9L, 0));

        final ChunkTasks task = queue.removeFirstTask();
        assertNotNull(task);
        assertEquals(9L, task.initialLightEdgeGeneration);
        assertEquals(0, task.edgeCheckAttempts);

        queue.completeTask(task);
    }
}
