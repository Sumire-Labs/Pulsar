/*
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.sumirelabs.pulsar.light;

import org.junit.jupiter.api.Test;

import com.sumirelabs.pulsar.api.ExtendedWorld;
import com.sumirelabs.pulsar.light.engine.*;
import com.sumirelabs.pulsar.util.*;

import net.minecraft.init.Bootstrap;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

import static org.junit.jupiter.api.Assertions.*;

/** Fault injection into the real scalar task and completion paths. */
class TaskFailureRecoveryTest {

    @org.junit.jupiter.api.BeforeAll
    static void initializeLoader() throws Exception {
        Bootstrap.register();
        var mods = net.minecraftforge.fml.common.Loader.class.getDeclaredField("namedMods");
        mods.setAccessible(true);
        if (mods.get(net.minecraftforge.fml.common.Loader.instance()) == null)
            mods.set(net.minecraftforge.fml.common.Loader.instance(), new java.util.HashMap<>());
    }

    static class BrokenWorld extends WorldServer implements ExtendedWorld {

        boolean fail;
        int calls;
        BrokenWorld() {
            super(null, null, null, 0, null);
        }
        public Chunk pulsar$getAnyChunkImmediately(int x, int z) {
            calls++;
            if (fail)
                throw new IllegalStateException("INJECTED_LIGHT_FAILURE");
            return null;
        }
        public boolean pulsar$hasChunkPendingLight(int x, int z) {
            return false;
        }
        public void pulsar$flushLightUpdates() {}

    }
    static class AuditChunk extends Chunk implements PulsarChunk {

        boolean ready, usable, saved;
        int publications;
        AuditChunk() {
            super(null, 0, 0);
        }
        public boolean pulsar$isLightReady() {
            return ready;
        }
        public void pulsar$setLightReady(boolean v) {
            ready = v;
        }
        public boolean pulsar$isLightUsable() {
            return usable;
        }
        public void pulsar$setLightUsable(boolean v) {
            usable = v;
        }
        public void pulsar$markLightDirty() {}
        public void pulsar$clearLightDirty() {}
        private net.minecraft.world.chunk.storage.ExtendedBlockStorage[] sections;
        @Override
        public net.minecraft.world.chunk.storage.ExtendedBlockStorage[] getBlockStorageArray() {
            if (sections == null)
                sections = new net.minecraft.world.chunk.storage.ExtendedBlockStorage[16];
            return sections;
        }
        public void pulsar$syncLightToVanilla() {
            publications++;
        }
        public void pulsar$setSavedLightValid(boolean v) {
            saved = v;
        }
        public boolean pulsar$hasSavedLightValid() {
            return false;
        }
        public SWMRNibbleArray[] pulsar$getBlockNibbles() {
            return null;
        }
        public void pulsar$setBlockNibbles(SWMRNibbleArray[] v) {}
        public SWMRNibbleArray[] pulsar$getSkyNibbles() {
            return null;
        }
        public void pulsar$setSkyNibbles(SWMRNibbleArray[] v) {}
        public boolean[] pulsar$getBlockEmptinessMap() {
            return null;
        }
        public void pulsar$setBlockEmptinessMap(boolean[] v) {}
        public boolean[] pulsar$getSkyEmptinessMap() {
            return null;
        }
        public void pulsar$setSkyEmptinessMap(boolean[] v) {}

    }
    @Test
    void failedEdgeTasksAreNeverPublished() throws Exception {
        for (boolean sky : new boolean[]{false, true})
            run(sky, false);
    }
    @Test
    void exhaustedEdgeRetriesAreNeverPublished() throws Exception {
        for (boolean sky : new boolean[]{false, true})
            run(sky, true);
    }
    @Test
    void staleFailureCannotInvalidateANewerGenerationAndRecoveryCanPublish() throws Exception {
        Bootstrap.register();
        var chunk = allocate(AuditChunk.class);
        var loaded = new SnapshotChunkMap();
        loaded.put(0, chunk);
        var queue = new LightQueue(WorldHeightContext.VANILLA);
        var coordinator = new InitialLightCoordinator(loaded, null, queue);
        var first = coordinator.queue(0, 0, chunk, new Boolean[16]);
        var stale = queue.removeFirstTask();
        var replacement = coordinator.queue(0, 0, chunk, new Boolean[16]);
        coordinator.fail(stale, new IllegalStateException("Old failure"));
        queue.completeTask(stale);
        assertTrue(coordinator.isCurrent(0, replacement.generation));
        assertFalse(replacement.future.isDone());
        var retry = queue.removeFirstTask();
        coordinator.completeInitial(retry, 2);
        queue.completeTask(retry);
        var edge = queue.removeFirstTask();
        coordinator.completeEdges(edge, 2);
        queue.completeTask(edge);
        assertTrue(chunk.ready);
        assertTrue(replacement.published);
        assertFalse(first.published);
        var failing = coordinator.queue(0, 0, chunk, new Boolean[16]);
        var task = queue.removeFirstTask();
        chunk.saved = true;
        coordinator.fail(task, new IllegalStateException("Current failure"));
        queue.completeTask(task);
        assertFalse(chunk.ready);
        assertFalse(chunk.usable);
        assertFalse(chunk.saved);
        assertThrows(java.util.concurrent.ExecutionException.class,
            () -> failing.future.get(2, java.util.concurrent.TimeUnit.SECONDS));
        var recovered = coordinator.queue(0, 0, chunk, new Boolean[16]);
        task = queue.removeFirstTask();
        coordinator.completeInitial(task, 2);
        queue.completeTask(task);
        edge = queue.removeFirstTask();
        coordinator.completeEdges(edge, 2);
        queue.completeTask(edge);
        assertTrue(chunk.ready);
        assertTrue(recovered.published);
    }

    @Test
    void replacementFailureReachesWaitersFromThePreviousRecoveryGeneration() throws Exception {
        Bootstrap.register();
        var chunk = allocate(AuditChunk.class);
        var loaded = new SnapshotChunkMap();
        loaded.put(0, chunk);
        var queue = new LightQueue(WorldHeightContext.VANILLA);
        var coordinator = new InitialLightCoordinator(loaded, null, queue);
        var original = coordinator.queue(0, 0, chunk, new Boolean[16]);
        var recovered = coordinator.queueRecovery(0, 0, chunk, new Boolean[16], 1);
        var task = queue.removeFirstTask();
        var cause = new IllegalStateException("Recovery failed");
        coordinator.fail(task, cause);
        queue.completeTask(task);
        assertSame(cause,
            assertThrows(java.util.concurrent.ExecutionException.class,
                () -> original.future.get(2, java.util.concurrent.TimeUnit.SECONDS)).getCause());
        assertSame(cause,
            assertThrows(java.util.concurrent.ExecutionException.class,
                () -> recovered.future.get(2, java.util.concurrent.TimeUnit.SECONDS)).getCause());
        assertFalse(original.published);
        assertFalse(chunk.ready);
    }

    @Test
    void rgbDispatchFailuresTerminateTheirGenerationInsteadOfLeavingWaitersHanging() throws Exception {
        Bootstrap.register();
        LightStats.enabled = false;
        var enabled = com.sumirelabs.pulsar.core.RgbMixinSelection.class.getDeclaredField("enabled");
        enabled.setAccessible(true);
        boolean old = enabled.getBoolean(null);
        enabled.setBoolean(null, true);
        try {
            for (boolean sky : new boolean[]{false, true}) {
                var world = allocate(BrokenWorld.class);
                world.fail = true;
                var chunk = allocate(AuditChunk.class);
                var loaded = new SnapshotChunkMap();
                loaded.put(0, chunk);
                var queue = new LightQueue(WorldHeightContext.VANILLA);
                var coordinator = new InitialLightCoordinator(loaded, sky ? queue : null, sky ? null : queue);
                var completion = coordinator.queue(0, 0, chunk, new Boolean[16]);
                var initial = queue.removeFirstTask();
                coordinator.completeInitial(initial, sky ? 1 : 2);
                queue.completeTask(initial);
                var edge = queue.removeFirstTask();
                var manager = allocate(WorldLightManager.class);
                set(manager, "world", world);
                set(manager, "loadedChunkMap", loaded);
                set(manager, "initialLighting", coordinator);
                set(manager, "skyQueue", sky ? queue : null);
                set(manager, "blockQueue", sky ? null : queue);
                set(manager, "lightingGate", new java.util.concurrent.locks.ReentrantReadWriteLock());
                PulsarEngine engine = sky
                    ? new RgbSkyEngine(world, WorldHeightContext.VANILLA)
                    : new RgbBlockEngine(world, WorldHeightContext.VANILLA);
                var process = WorldLightManager.class.getDeclaredMethod("processWithGate", ChunkTasks.class,
                    PulsarEngine.class, boolean.class);
                process.setAccessible(true);
                assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> process.invoke(manager, edge, engine, sky));
                queue.completeTask(edge);
                assertFalse(chunk.ready);
                assertFalse(completion.published);
                assertFalse(coordinator.hasPending(0));
                assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> completion.future.get(2, java.util.concurrent.TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> edge.onComplete.get(2, java.util.concurrent.TimeUnit.SECONDS));
            }
        } finally {
            enabled.setBoolean(null, old);
        }
    }

    @Test
    void initialPropagationExceptionsFailTheGenerationBeforeItBecomesUsable() throws Exception {
        Bootstrap.register();
        LightStats.enabled = false;
        for (boolean sky : new boolean[]{false, true}) {
            var world = allocate(BrokenWorld.class);
            world.fail = true;
            var chunk = allocate(AuditChunk.class);
            var loaded = new SnapshotChunkMap();
            loaded.put(0, chunk);
            var queue = new LightQueue(WorldHeightContext.VANILLA);
            var coordinator = new InitialLightCoordinator(loaded, sky ? queue : null, sky ? null : queue);
            var completion = coordinator.queue(0, 0, chunk, new Boolean[16]);
            var task = queue.removeFirstTask();
            var manager = allocate(WorldLightManager.class);
            set(manager, "loadedChunkMap", loaded);
            set(manager, "initialLighting", coordinator);
            PulsarEngine engine = sky
                ? new ScalarSkyEngine(world, WorldHeightContext.VANILLA)
                : new ScalarBlockEngine(world, WorldHeightContext.VANILLA);
            var process = WorldLightManager.class.getDeclaredMethod(sky ? "processSkyTask" : "processBlockTask",
                ChunkTasks.class, PulsarEngine.class);
            process.setAccessible(true);
            process.invoke(manager, task, engine);
            queue.completeTask(task);
            assertTrue(world.calls > 0);
            assertFalse(chunk.ready);
            assertFalse(chunk.usable);
            assertFalse(completion.published);
            assertEquals("INJECTED_LIGHT_FAILURE",
                assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> completion.future.get(2, java.util.concurrent.TimeUnit.SECONDS)).getCause()
                    .getMessage());
            assertThrows(java.util.concurrent.ExecutionException.class,
                () -> task.onComplete.get(2, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(coordinator.hasPending(0));
        }
    }

    @Test
    void rgbEdgeRetryBudgetCannotResetAcrossReplacementGenerations() throws Exception {
        Bootstrap.register();
        var chunk = allocate(AuditChunk.class);
        var loaded = new SnapshotChunkMap();
        loaded.put(0, chunk);
        var queue = new LightQueue(WorldHeightContext.VANILLA);
        var coordinator = new InitialLightCoordinator(loaded, null, queue);
        var original = coordinator.queue(0, 0, chunk, new Boolean[16]);
        var initial = queue.removeFirstTask();
        coordinator.completeInitial(initial, 2);
        queue.completeTask(initial);
        var manager = allocate(WorldLightManager.class);
        set(manager, "loadedChunkMap", loaded);
        set(manager, "initialLighting", coordinator);
        var retry = WorldLightManager.class.getDeclaredMethod("requeueAfterOverflow", LightQueue.class,
            ChunkTasks.class, int.class, int.class, String.class);
        retry.setAccessible(true);
        for (int attempt = 0; attempt <= InitialLightCoordinator.MAX_RELIGHT_ATTEMPTS; attempt++) {
            var edge = queue.removeFirstTask();
            assertNotNull(edge);
            assertEquals(attempt, edge.edgeCheckAttempts);
            boolean scheduled = (boolean) retry.invoke(manager, queue, edge, 0, 0, "RGB");
            queue.completeTask(edge);
            assertEquals(attempt < InitialLightCoordinator.MAX_RELIGHT_ATTEMPTS, scheduled);
            assertFalse(chunk.ready);
            assertFalse(original.published);
            if (scheduled) {
                assertFalse(original.future.isDone());
                initial = queue.removeFirstTask();
                assertNotNull(initial);
                coordinator.completeInitial(initial, 2);
                queue.completeTask(initial);
            } else {
                assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> original.future.get(2, java.util.concurrent.TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> edge.onComplete.get(2, java.util.concurrent.TimeUnit.SECONDS));
            }
        }
        assertFalse(coordinator.hasPending(0));
        assertFalse(queue.hasWork());
    }

    @Test
    void failureOfAnOrdinaryEditInvalidatesReadyAndSavedLighting() throws Exception {
        Bootstrap.register();
        var chunk = allocate(AuditChunk.class);
        chunk.ready = chunk.usable = chunk.saved = true;
        var loaded = new SnapshotChunkMap();
        loaded.put(0, chunk);
        var queue = new LightQueue(WorldHeightContext.VANILLA);
        var coordinator = new InitialLightCoordinator(loaded, null, queue);
        queue.queueBlockChange(0, 64, 0);
        var task = queue.removeFirstTask();
        coordinator.fail(task, new IllegalStateException("Edit failed"));
        queue.completeTask(task);
        assertFalse(chunk.ready);
        assertFalse(chunk.usable);
        assertFalse(chunk.saved);
        assertThrows(java.util.concurrent.ExecutionException.class,
            () -> task.onComplete.get(2, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(coordinator.hasFailed(0));
        var manager = allocate(WorldLightManager.class);
        set(manager, "initialLighting", coordinator);
        assertTrue(manager.hasFailedLightWorkNear(1, 1));
        assertFalse(manager.hasFailedLightWorkNear(2, 0));
        coordinator.queue(0, 0, chunk, new Boolean[16]);
        assertFalse(coordinator.hasFailed(0));
        var next = queue.removeFirstTask();
        coordinator.fail(next, new IllegalStateException("Another failure"));
        queue.completeTask(next);
        assertTrue(coordinator.hasFailed(0));
        coordinator.removeChunk(0, 0);
        assertFalse(coordinator.hasFailed(0));
    }

    private static void run(boolean sky, boolean overflow) throws Exception {
        Bootstrap.register();
        LightStats.enabled = false;
        var world = allocate(BrokenWorld.class);
        world.fail = !overflow;
        var chunk = allocate(AuditChunk.class);
        var loaded = new SnapshotChunkMap();
        loaded.put(0, chunk);
        var skyQueue = new LightQueue(WorldHeightContext.VANILLA);
        var blockQueue = new LightQueue(WorldHeightContext.VANILLA);
        var queue = sky ? skyQueue : blockQueue;
        var other = sky ? blockQueue : skyQueue;
        var coordinator = new InitialLightCoordinator(loaded, skyQueue, blockQueue);
        var completion = coordinator.queue(0, 0, chunk, new Boolean[16]);
        var initial = queue.removeFirstTask();
        coordinator.completeInitial(initial, sky ? 1 : 2);
        queue.completeTask(initial);
        var otherInitial = other.removeFirstTask();
        coordinator.completeInitial(otherInitial, sky ? 2 : 1);
        other.completeTask(otherInitial);
        var otherEdge = other.removeFirstTask();
        coordinator.completeEdges(otherEdge, sky ? 2 : 1);
        other.completeTask(otherEdge);
        var edge = queue.removeFirstTask();
        assertNotNull(edge);
        edge.edgeCheckAttempts = InitialLightCoordinator.MAX_RELIGHT_ATTEMPTS;
        var manager = allocate(WorldLightManager.class);
        set(manager, "loadedChunkMap", loaded);
        set(manager, "initialLighting", coordinator);
        set(manager, "skyQueue", skyQueue);
        set(manager, "blockQueue", blockQueue);
        PulsarEngine engine = sky ? new ScalarSkyEngine(world, WorldHeightContext.VANILLA) {

            public boolean wasQueueOverflowed() {
                return overflow;
            }

        }
            : new ScalarBlockEngine(world, WorldHeightContext.VANILLA) {

                public boolean wasQueueOverflowed() {
                    return overflow;
                }

            };
        var process = WorldLightManager.class.getDeclaredMethod(sky ? "processSkyTask" : "processBlockTask",
            ChunkTasks.class, PulsarEngine.class);
        process.setAccessible(true);
        process.invoke(manager, edge, engine);
        queue.completeTask(edge);
        assertTrue(world.calls > 0);
        assertFalse(chunk.ready, "Failed lighting became ready");
        assertFalse(chunk.usable);
        assertFalse(completion.published);
        assertTrue(completion.future.isDone());
        assertThrows(java.util.concurrent.ExecutionException.class,
            () -> completion.future.get(2, java.util.concurrent.TimeUnit.SECONDS));
        assertThrows(java.util.concurrent.ExecutionException.class,
            () -> edge.onComplete.get(2, java.util.concurrent.TimeUnit.SECONDS));
        assertFalse(queue.hasWork());
        assertFalse(other.hasWork());
        System.out.println("FAILURE_REJECTED sky=" + sky + " overflow=" + overflow + " ready=" + chunk.ready
            + " published=" + completion.published);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        var f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
    private static <T> T allocate(Class<T> type) throws Exception {
        var cls = Class.forName("sun.misc.Unsafe");
        var f = cls.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return type.cast(cls.getMethod("allocateInstance", Class.class).invoke(f.get(null), type));
    }

}
