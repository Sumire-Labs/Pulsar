package com.sumirelabs.pulsar.light;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ContextualLightSnapshotTest {
    @Test
    void workersReadPublishedBlockAndFluidWithoutExecutingWorldCallbacks() throws Exception {
        final var snapshot = new ContextualLightSnapshot<Object>();
        final Object block = new Object();
        final Object fluid = new Object();
        snapshot.publish(12, block, 0x1234, fluid, 0x5678);
        try (var executor = Executors.newFixedThreadPool(2)) {
            final var sky = executor.submit(() -> snapshot.read(12, block, -1));
            final var light = executor.submit(() -> snapshot.read(12, fluid, -1));
            assertEquals(0x1234, sky.get(5, TimeUnit.SECONDS));
            assertEquals(0x5678, light.get(5, TimeUnit.SECONDS));
        }
        assertFalse(snapshot.hasPending());
    }

    @Test
    void aReplacementNeverInheritsThePreviousTilesValues() {
        final var snapshot = new ContextualLightSnapshot<Object>();
        final Object original = new Object();
        final Object replacement = new Object();
        snapshot.publish(8, original, 15, null, 0);
        assertEquals(0, snapshot.read(8, replacement, 0));
        assertTrue(snapshot.hasPending());
        assertEquals(Set.of(8), snapshot.takePending());
        snapshot.publish(8, replacement, 7, null, 0);
        assertEquals(7, snapshot.read(8, replacement, 0));
    }

    @Test
    void explicitRefreshUpdatesATileWithoutAStateChangeAndRemovalDropsBothLayers() {
        final var snapshot = new ContextualLightSnapshot<Object>();
        final Object block = new Object();
        final Object fluid = new Object();
        snapshot.publish(0, block, 0, fluid, 10);
        snapshot.request(0);
        assertEquals(Set.of(0), snapshot.takePending());
        snapshot.publish(0, block, 15, null, 0);
        assertEquals(15, snapshot.read(0, block, -1));
        assertEquals(-1, snapshot.read(0, fluid, -1));
        snapshot.publish(0, null, 0, null, 0);
        assertFalse(snapshot.contains(0));
    }

    @Test
    void repeatedMissesCoalesceAndRequestsDuringCaptureSurviveForNextTick() {
        final var snapshot = new ContextualLightSnapshot<Object>();
        final Object state = new Object();
        for (int i = 0; i < 1000; i++) snapshot.read(27, state, 0);
        assertEquals(Set.of(27), snapshot.takePending());
        assertFalse(snapshot.hasPending());
        // A callback can itself request lighting. Publication must not erase it.
        snapshot.request(27);
        snapshot.publish(27, state, 12, null, 0);
        assertTrue(snapshot.hasPending());
        assertEquals(Set.of(27), snapshot.takePending());
    }

    @Test
    void aWorkerCannotPublishSamples() throws Exception {
        final var snapshot = new ContextualLightSnapshot<Object>();
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> assertThrows(IllegalStateException.class,
                    () -> snapshot.publish(0, new Object(), 15, null, 0))).get(5, TimeUnit.SECONDS);
        }
        assertFalse(snapshot.contains(0));
    }

    @Test
    void concurrentReplacementCannotExposeAnotherStatesLight() throws Exception {
        final var snapshot = new ContextualLightSnapshot<Object>();
        final Object a = new Object();
        final Object b = new Object();
        snapshot.publish(1, a, 3, b, 11);
        try (var executor = Executors.newFixedThreadPool(2)) {
            final var readerA = executor.submit(() -> {
                for (int i = 0; i < 100_000; i++) assertEquals(3, snapshot.read(1, a, -1));
            });
            final var readerB = executor.submit(() -> {
                for (int i = 0; i < 100_000; i++) assertEquals(11, snapshot.read(1, b, -1));
            });
            for (int i = 0; i < 10_000; i++) {
                snapshot.publish(1, b, 11, a, 3);
                snapshot.publish(1, a, 3, b, 11);
            }
            readerA.get(5, TimeUnit.SECONDS);
            readerB.get(5, TimeUnit.SECONDS);
        }
        assertFalse(snapshot.hasPending());
    }

    @Test
    void signedHeightAndLocalCoordinatesDoNotAlias() {
        assertNotEquals(ContextualLightManager.pack(-1, -1, -1), ContextualLightManager.pack(15, 255, 15));
        assertEquals(-64, ContextualLightManager.pack(31, -64, -17) >> 8);
        assertEquals(15, ContextualLightManager.pack(31, -64, -17) & 15);
        assertEquals(15, (ContextualLightManager.pack(31, -64, -17) >>> 4) & 15);
    }
}
