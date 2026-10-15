package com.sumirelabs.pulsar.light;

import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class PendingLightPositionsTest {
    @Test void detachesSignedPositionsAndDeduplicatesWithoutMutatingPreviousBatches() {
        var queue = new PendingLightPositions();
        queue.add(-1); queue.add(Integer.MIN_VALUE); queue.add(0); queue.add(-1);
        var first = queue.take();
        assertEquals(Set.of(-1, Integer.MIN_VALUE, 0), first);
        assertTrue(queue.isEmpty());
        queue.add(-1); queue.add(Integer.MAX_VALUE);
        assertEquals(Set.of(-1, Integer.MAX_VALUE), queue.take());
        assertEquals(Set.of(-1, Integer.MIN_VALUE, 0), first);
        assertTrue(queue.take().isEmpty());
    }

    @Test void callbacksAndWorkersCanRequestAgainWhileDetachedBatchIsBeingProcessed() throws Exception {
        var queue = new PendingLightPositions(); queue.add(27);
        var batch = queue.take();
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> { queue.add(27); queue.add(28); }).get(5, TimeUnit.SECONDS);
        }
        assertEquals(Set.of(27), batch);
        assertFalse(queue.isEmpty());
        assertEquals(Set.of(27, 28), queue.take());
        assertTrue(queue.isEmpty());
    }

    @Test void concurrentProducersAndDrainsLoseNoUniquePositions() throws Exception {
        var queue = new PendingLightPositions();
        var seen = new java.util.HashSet<Integer>();
        try (var executor = Executors.newFixedThreadPool(4)) {
            var start = new CountDownLatch(1);
            var done = new CountDownLatch(4);
            var futures = new java.util.ArrayList<Future<?>>();
            for (int thread = 0; thread < 4; thread++) {
                final int offset = thread * 10_000 - 20_000;
                futures.add(executor.submit(() -> {
                    try { start.await(); for (int i=0;i<10_000;i++) queue.add(offset+i); }
                    catch (InterruptedException e) { throw new AssertionError(e); }
                    finally { done.countDown(); }
                }));
            }
            start.countDown();
            while (!done.await(1, TimeUnit.MILLISECONDS)) seen.addAll(queue.take());
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
            seen.addAll(queue.take());
        }
        assertEquals(40_000, seen.size());
        for (int i=-20_000;i<20_000;i++) assertTrue(seen.contains(i));
        assertTrue(queue.isEmpty());
    }
}
