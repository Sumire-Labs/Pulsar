package com.sumirelabs.pulsar.light;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InitialLightCompletionStateTest {

    @Test
    void ignoresStaleGenerationsAndDuplicateLaneCompletion() {
        final InitialLightCompletionState state = new InitialLightCompletionState(
                7L, InitialLightCompletionState.SKY | InitialLightCompletionState.BLOCK);

        assertEquals(InitialLightCompletionState.Result.IGNORED,
                state.complete(6L, InitialLightCompletionState.SKY));
        assertEquals(InitialLightCompletionState.Result.WAITING,
                state.complete(7L, InitialLightCompletionState.SKY));
        assertEquals(InitialLightCompletionState.Result.IGNORED,
                state.complete(7L, InitialLightCompletionState.SKY));
        assertEquals(InitialLightCompletionState.Result.COMPLETE,
                state.complete(7L, InitialLightCompletionState.BLOCK));
        assertEquals(InitialLightCompletionState.Result.IGNORED,
                state.complete(7L, InitialLightCompletionState.BLOCK));
    }

    @Test
    void completesAfterTheOnlyRequiredLane() {
        final InitialLightCompletionState state = new InitialLightCompletionState(
                12L, InitialLightCompletionState.BLOCK);

        assertEquals(InitialLightCompletionState.Result.IGNORED,
                state.complete(12L, InitialLightCompletionState.SKY));
        assertEquals(InitialLightCompletionState.Result.COMPLETE,
                state.complete(12L, InitialLightCompletionState.BLOCK));
    }

    @Test
    void acceptsEachLaneExactlyOnceWhenWorkersFinishConcurrently() throws Exception {
        final InitialLightCompletionState state = new InitialLightCompletionState(
                21L, InitialLightCompletionState.SKY | InitialLightCompletionState.BLOCK);
        final CountDownLatch start = new CountDownLatch(1);
        final ExecutorService workers = Executors.newFixedThreadPool(3);
        try {
            final List<Future<InitialLightCompletionState.Result>> results = Arrays.asList(
                    workers.submit(() -> {
                        start.await();
                        return state.complete(21L, InitialLightCompletionState.SKY);
                    }),
                    workers.submit(() -> {
                        start.await();
                        return state.complete(21L, InitialLightCompletionState.SKY);
                    }),
                    workers.submit(() -> {
                        start.await();
                        return state.complete(21L, InitialLightCompletionState.BLOCK);
                    }));
            start.countDown();

            int ignored = 0;
            int waiting = 0;
            int complete = 0;
            for (final Future<InitialLightCompletionState.Result> result : results) {
                switch (result.get(5L, TimeUnit.SECONDS)) {
                    case IGNORED:
                        ignored++;
                        break;
                    case WAITING:
                        waiting++;
                        break;
                    case COMPLETE:
                        complete++;
                        break;
                }
            }
            assertEquals(1, ignored);
            assertEquals(1, waiting);
            assertEquals(1, complete);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void rejectsInvalidGenerationAndLaneMasks() {
        assertThrows(IllegalArgumentException.class,
                () -> new InitialLightCompletionState(0L, InitialLightCompletionState.BLOCK));
        assertThrows(IllegalArgumentException.class,
                () -> new InitialLightCompletionState(1L, 0));

        final InitialLightCompletionState state = new InitialLightCompletionState(
                1L, InitialLightCompletionState.SKY | InitialLightCompletionState.BLOCK);
        assertThrows(IllegalArgumentException.class,
                () -> state.complete(1L, 0));
        assertThrows(IllegalArgumentException.class,
                () -> state.complete(1L, InitialLightCompletionState.SKY | InitialLightCompletionState.BLOCK));
    }
}
