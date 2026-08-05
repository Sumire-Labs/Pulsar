package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.util.CoordinateUtils;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightQueueTest {

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
