package com.sumirelabs.pulsar.light;

import com.google.common.util.concurrent.SettableFuture;
import com.sumirelabs.pulsar.Pulsar;
import com.sumirelabs.pulsar.light.engine.PulsarEngine;
import com.sumirelabs.pulsar.light.engine.ScalarBlockEngine;
import com.sumirelabs.pulsar.light.engine.ScalarSkyEngine;
import com.sumirelabs.pulsar.util.CoordinateUtils;
import com.sumirelabs.pulsar.util.SnapshotChunkMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Per-{@link World} light manager. Owns the worker threads, engine pools and
 * task queues that drive Pulsar's BFS lighting. Ported from SuperNova
 * {@code WorldLightManager} (1.7.10), with the RGB engine factories removed
 * and the API surface simplified for scalar mode only.
 */
public final class WorldLightManager {

    private final World world;
    private final boolean hasSkyLight;
    private final boolean hasBlockLight;

    private final ConcurrentLinkedDeque<PulsarEngine> cachedSkyPropagators;
    private final ConcurrentLinkedDeque<PulsarEngine> cachedBlockPropagators;
    private final Supplier<PulsarEngine> skyEngineFactory;
    private final Supplier<PulsarEngine> blockEngineFactory;

    private final SnapshotChunkMap loadedChunkMap = new SnapshotChunkMap();

    // Tracks in-flight light work per chunk — used by awaitPendingWork to
    // ensure chunk save reads post-BFS data on unload.
    private final Long2ObjectOpenHashMap<SettableFuture<Void>> pendingWork = new Long2ObjectOpenHashMap<>();

    // Queues for sky and block light. On the server both are drained by one
    // worker thread (interleaving a few initial lights at a time so neither
    // engine's chunks starve); on the client (thin mode) both are drained on
    // the main thread once per tick, so the engines can share storage with
    // the vanilla nibbles and mark render updates directly.
    private final LightQueue skyQueue;
    private final LightQueue blockQueue;
    private final Semaphore workSignal = new Semaphore(0);
    private final Thread lightWorkerThread;
    private volatile boolean running = true;

    private final LightStats stats;

    // Coordination for initial chunk lighting: both engines must finish before setLightReady(true).
    private final Long2ObjectOpenHashMap<ChunkLightCompletion> initialLightCompletions = new Long2ObjectOpenHashMap<>();

    private static final int MAX_RELIGHT_ATTEMPTS = 2;
    private static final long EDGE_CHECK_BUDGET_NS = 10_000_000L; // 10ms
    private static final long BLOCK_CHANGE_BUDGET_NS = 5_000_000L; // 5ms
    // Initial-light tasks processed per queue before yielding to the other
    // queue — keeps sky and block completion (and thus lightReady) close
    // together on the single worker during worldgen bursts.
    private static final int INITIAL_LIGHT_INTERLEAVE = 8;

    public WorldLightManager(final World world, final boolean hasSkyLight, final boolean hasBlockLight) {
        this.world = world;
        this.hasSkyLight = hasSkyLight;
        this.hasBlockLight = hasBlockLight;
        this.cachedSkyPropagators = hasSkyLight ? new ConcurrentLinkedDeque<>() : null;
        this.cachedBlockPropagators = hasBlockLight ? new ConcurrentLinkedDeque<>() : null;

        this.skyEngineFactory = hasSkyLight ? () -> new ScalarSkyEngine(world) : null;
        this.blockEngineFactory = hasBlockLight ? () -> new ScalarBlockEngine(world) : null;

        this.skyQueue = hasSkyLight ? new LightQueue(this.workSignal) : null;
        this.blockQueue = hasBlockLight ? new LightQueue(this.workSignal) : null;
        this.stats = new LightStats(world.isRemote);
        if (this.skyQueue != null) this.skyQueue.setStats(this.stats);
        if (this.blockQueue != null) this.blockQueue.setStats(this.stats);

        if ((hasSkyLight || hasBlockLight) && !world.isRemote) {
            // One worker for both queues: same total work as the former
            // sky/block thread pair but half the threads competing with the
            // render/chunk-build threads in singleplayer (Starlight upstream
            // is also single-lane).
            this.lightWorkerThread = new Thread(
                    () -> {
                        while (this.running) {
                            if ((this.skyQueue == null || this.skyQueue.isEmpty())
                                    && (this.blockQueue == null || this.blockQueue.isEmpty())) {
                                try {
                                    this.workSignal.acquire();
                                    this.workSignal.drainPermits();
                                } catch (final InterruptedException e) {
                                    break;
                                }
                            }
                            this.propagateSkyChanges(INITIAL_LIGHT_INTERLEAVE);
                            this.propagateBlockChanges(INITIAL_LIGHT_INTERLEAVE);
                        }
                    }, "Pulsar-Light");
            this.lightWorkerThread.setDaemon(true);
            this.lightWorkerThread.start();
        } else {
            this.lightWorkerThread = null;
        }
    }

    public void registerChunk(final Chunk chunk) {
        this.loadedChunkMap.put(CoordinateUtils.getChunkKey(chunk.x, chunk.z), chunk);
    }

    public void unregisterChunk(final int cx, final int cz) {
        this.loadedChunkMap.remove(CoordinateUtils.getChunkKey(cx, cz));
    }

    public Chunk getLoadedChunk(final int chunkX, final int chunkZ) {
        return this.loadedChunkMap.get(CoordinateUtils.getChunkKey(chunkX, chunkZ));
    }

    private static PulsarEngine getEngine(final ConcurrentLinkedDeque<PulsarEngine> cache,
                                          final Supplier<PulsarEngine> factory) {
        if (cache == null) return null;
        final PulsarEngine ret = cache.pollFirst();
        return ret != null ? ret : factory.get();
    }

    private static void releaseEngine(final ConcurrentLinkedDeque<PulsarEngine> cache, final PulsarEngine engine) {
        if (cache == null || engine == null) return;
        if (cache.size() < 4) {
            cache.addFirst(engine);
        }
    }

    public void queueBlockChange(final int x, final int y, final int z) {
        if (this.skyQueue != null) this.skyQueue.queueBlockChange(x, y, z);
        if (this.blockQueue != null) this.blockQueue.queueBlockChange(x, y, z);
    }

    public void queueChunkLight(final int cx, final int cz, final Chunk chunk, final Boolean[] emptySections) {
        final int engineCount = (this.hasSkyLight ? 1 : 0) + (this.hasBlockLight ? 1 : 0);
        final ChunkLightCompletion completion = new ChunkLightCompletion(engineCount, chunk);
        final long key = CoordinateUtils.getChunkKey(cx, cz);

        synchronized (this.initialLightCompletions) {
            this.initialLightCompletions.put(key, completion);
        }

        if (this.skyQueue != null) this.skyQueue.queueChunkLight(cx, cz, chunk, emptySections);
        if (this.blockQueue != null) this.blockQueue.queueChunkLight(cx, cz, chunk, emptySections);
    }

    /**
     * Queue the cheap load-time init for a chunk restored with valid
     * persisted light. No completion latch: the caller has already set
     * {@code lightReady}.
     */
    public void queueChunkLoadInit(final int cx, final int cz, final Chunk chunk, final Boolean[] emptySections) {
        if (this.skyQueue != null) this.skyQueue.queueChunkLoadInit(cx, cz, chunk, emptySections);
        if (this.blockQueue != null) this.blockQueue.queueChunkLoadInit(cx, cz, chunk, emptySections);
    }

    public void removeChunkFromQueues(final int cx, final int cz) {
        if (this.skyQueue != null) this.skyQueue.removeChunk(cx, cz);
        if (this.blockQueue != null) this.blockQueue.removeChunk(cx, cz);
        final long key = CoordinateUtils.getChunkKey(cx, cz);
        synchronized (this.initialLightCompletions) {
            this.initialLightCompletions.remove(key);
        }
    }

    public boolean hasUpdates() {
        return (this.skyQueue != null && !this.skyQueue.isEmpty()) || (this.blockQueue != null && !this.blockQueue.isEmpty());
    }

    public boolean hasChunkPendingLight(final int cx, final int cz) {
        return (this.skyQueue != null && this.skyQueue.hasPendingWork(cx, cz))
                || (this.blockQueue != null && this.blockQueue.hasPendingWork(cx, cz));
    }

    /**
     * Thin-client tick: drain both light queues on the main thread. The
     * engines write into SWMR arrays that share storage with the vanilla
     * nibbles and mark render updates directly, so there is no separate
     * publish/drain step.
     */
    public void processClientRenderUpdates() {
        this.propagateSkyChanges();
        this.propagateBlockChanges();
        // No worker consumes the permits on the client — drop them so the
        // semaphore doesn't accumulate without bound.
        this.workSignal.drainPermits();
        final int skySize = this.skyQueue != null ? this.skyQueue.size() : 0;
        final int blockSize = this.blockQueue != null ? this.blockQueue.size() : 0;
        this.stats.tick(skySize, blockSize);
    }

    public void scheduleUpdate() {
        final int skySize = this.skyQueue != null ? this.skyQueue.size() : 0;
        final int blockSize = this.blockQueue != null ? this.blockQueue.size() : 0;
        this.stats.tick(skySize, blockSize);
    }

    private void propagateSkyChanges() {
        this.propagateSkyChanges(Integer.MAX_VALUE);
    }

    private void propagateBlockChanges() {
        this.propagateBlockChanges(Integer.MAX_VALUE);
    }

    private void propagateSkyChanges(final int maxInitialLights) {
        this.propagateChanges(this.skyQueue, this.cachedSkyPropagators, this.skyEngineFactory,
                this::processSkyTask, this.stats.skyChangeBudgetYields, "propagateSkyChanges", maxInitialLights);
    }

    private void propagateBlockChanges(final int maxInitialLights) {
        this.propagateChanges(this.blockQueue, this.cachedBlockPropagators, this.blockEngineFactory,
                this::processBlockTask, this.stats.blockChangeBudgetYields, "propagateBlockChanges", maxInitialLights);
    }

    private void propagateChanges(final LightQueue queue, final ConcurrentLinkedDeque<PulsarEngine> cache,
                                  final Supplier<PulsarEngine> factory,
                                  final BiConsumer<ChunkTasks, PulsarEngine> taskProcessor,
                                  final AtomicInteger changeBudgetYield, final String label,
                                  final int maxInitialLights) {
        final PulsarEngine engine = getEngine(cache, factory);
        if (engine == null) return;
        try {
            final long changeBudget = System.nanoTime() + BLOCK_CHANGE_BUDGET_NS;
            ChunkTasks task;
            while ((task = queue.removeFirstBlockChangeTask()) != null) {
                taskProcessor.accept(task, engine);
                if (System.nanoTime() > changeBudget) {
                    if (LightStats.enabled) changeBudgetYield.incrementAndGet();
                    break;
                }
            }

            boolean moreWork = true;
            while (moreWork) {
                moreWork = false;
                int initialLights = 0;
                while (initialLights < maxInitialLights && (task = queue.removeFirstInitialLightTask()) != null) {
                    ++initialLights;
                    taskProcessor.accept(task, engine);
                    ChunkTasks priorityTask;
                    while ((priorityTask = queue.removeFirstBlockChangeTask()) != null) {
                        taskProcessor.accept(priorityTask, engine);
                    }
                }
                if (initialLights >= maxInitialLights && queue.hasInitialLightTask()) {
                    // Interleave cap hit: yield so the other queue's initial
                    // lights (and thus lightReady completions) keep pace.
                    return;
                }
                final long edgeDeadline = System.nanoTime() + EDGE_CHECK_BUDGET_NS;
                while ((task = queue.removeFirstTask()) != null) {
                    taskProcessor.accept(task, engine);
                    ChunkTasks priorityTask;
                    while ((priorityTask = queue.removeFirstBlockChangeTask()) != null) {
                        taskProcessor.accept(priorityTask, engine);
                    }
                    if (queue.hasInitialLightTask()) {
                        moreWork = true;
                        break;
                    }
                    if (System.nanoTime() > edgeDeadline) {
                        if (LightStats.enabled) this.stats.edgeBudgetYields.incrementAndGet();
                        break;
                    }
                }
            }
        } catch (final Throwable t) {
            Pulsar.LOGGER.error("Exception in " + label, t);
        } finally {
            releaseEngine(cache, engine);
        }
    }

    private void processSkyTask(final ChunkTasks task, final PulsarEngine skyEngine) {
        final boolean statsOn = LightStats.enabled;
        final long t0 = statsOn ? System.nanoTime() : 0L;
        final int cx = CoordinateUtils.getChunkX(task.chunkCoordinate);
        final int cz = CoordinateUtils.getChunkZ(task.chunkCoordinate);

        if (this.loadedChunkMap.get(task.chunkCoordinate) == null) {
            this.completeInitialLighting(task.chunkCoordinate);
            return;
        }

        if (statsOn) {
            this.stats.chunksProcessed.incrementAndGet();
            this.stats.recordQueueLatency(task.enqueueTimeNs);
            skyEngine.setStats(this.stats);
        }

        try {
            if (task.loadInitChunk != null && task.initialLightChunk == null) {
                // Persisted-light chunk: nibble/emptiness-map init only, no BFS.
                skyEngine.loadInChunk(task.loadInitChunk, task.loadInitEmptySections);
            }

            if (task.initialLightChunk != null) {
                if (statsOn) this.stats.initialLightsRun.incrementAndGet();
                // checkEdges=true: seams are verified inline while the caches
                // are already set up (Starlight upstream's light() path).
                // Seams to not-yet-lit neighbours are covered later by the
                // neighbour's own inline check when it lights up.
                skyEngine.light(task.initialLightChunk, task.initialLightEmptySections, true);
                this.completeInitialLighting(task.chunkCoordinate);
            }

            if (task.changedSectionSet != null || (task.changedPositions != null && !task.changedPositions.isEmpty())) {
                skyEngine.blocksChangedInChunk(cx, cz, task.changedPositions, task.changedSectionSet);
            }

            if (skyEngine.wasQueueOverflowed()) {
                if (task.relightAttempts < MAX_RELIGHT_ATTEMPTS) {
                    final Chunk chunk = this.loadedChunkMap.get(task.chunkCoordinate);
                    if (chunk != null) {
                        this.skyQueue.requeueChunkLight(cx, cz, chunk,
                                PulsarEngine.getEmptySectionsForChunk(chunk), task.relightAttempts);
                    }
                } else {
                    Pulsar.LOGGER.error("Sky engine: chunk ({}, {}) overflowed BFS queue {} times - giving up.",
                            cx, cz, task.relightAttempts + 1);
                }
            }
        } catch (final Throwable t) {
            // Always complete the latch: with chunk sending gated on
            // lightReady, a leaked completion would leave the chunk unsent
            // forever.
            this.completeInitialLighting(task.chunkCoordinate);
            if (this.loadedChunkMap.get(task.chunkCoordinate) != null) {
                Pulsar.LOGGER.error("Sky task for chunk ({}, {}) failed", cx, cz, t);
            } else {
                Pulsar.LOGGER.warn("Sky task for chunk ({}, {}) aborted - chunk unloaded during processing", cx, cz, t);
            }
        }

        skyEngine.setStats(null);
        if (statsOn) {
            this.stats.skyWorkerTimeNs.addAndGet(System.nanoTime() - t0);
            this.stats.skyTasksProcessed.incrementAndGet();
        }
    }

    private void processBlockTask(final ChunkTasks task, final PulsarEngine blockEngine) {
        final boolean statsOn = LightStats.enabled;
        // t0/t1/t2 stay unconditional: they also feed the slow-task warning.
        final long t0 = System.nanoTime();
        final int cx = CoordinateUtils.getChunkX(task.chunkCoordinate);
        final int cz = CoordinateUtils.getChunkZ(task.chunkCoordinate);

        if (this.loadedChunkMap.get(task.chunkCoordinate) == null) {
            this.completeInitialLighting(task.chunkCoordinate);
            return;
        }

        if (statsOn) {
            this.stats.chunksProcessed.incrementAndGet();
            this.stats.recordQueueLatency(task.enqueueTimeNs);
            blockEngine.setStats(this.stats);
        }

        long changesNs = 0;
        int changesPos = 0, changesBfsInc = 0, changesBfsDec = 0;

        try {
            if (task.loadInitChunk != null && task.initialLightChunk == null) {
                // Persisted-light chunk: nibble/emptiness-map init only, no BFS.
                blockEngine.loadInChunk(task.loadInitChunk, task.loadInitEmptySections);
            }

            if (task.initialLightChunk != null) {
                // checkEdges=true: inline seam verification, see processSkyTask.
                blockEngine.light(task.initialLightChunk, task.initialLightEmptySections, true);
                this.completeInitialLighting(task.chunkCoordinate);
            }

            if (task.changedSectionSet != null || (task.changedPositions != null && !task.changedPositions.isEmpty())) {
                final long t1 = System.nanoTime();
                blockEngine.blocksChangedInChunk(cx, cz, task.changedPositions, task.changedSectionSet);
                changesNs = System.nanoTime() - t1;
                changesPos = blockEngine.lastPositionsProcessed;
                changesBfsInc = blockEngine.lastBfsIncreaseTotal;
                changesBfsDec = blockEngine.lastBfsDecreaseTotal;
                if (statsOn) this.stats.blockPositionsProcessed.addAndGet(changesPos);
            }

            if (blockEngine.wasQueueOverflowed()) {
                if (task.relightAttempts < MAX_RELIGHT_ATTEMPTS) {
                    final Chunk chunk = this.loadedChunkMap.get(task.chunkCoordinate);
                    if (chunk != null) {
                        this.blockQueue.requeueChunkLight(cx, cz, chunk,
                                PulsarEngine.getEmptySectionsForChunk(chunk), task.relightAttempts);
                    }
                } else {
                    Pulsar.LOGGER.error("Block engine: chunk ({}, {}) overflowed BFS queue {} times - giving up.",
                            cx, cz, task.relightAttempts + 1);
                }
            }
        } catch (final Throwable t) {
            // Always complete the latch: with chunk sending gated on
            // lightReady, a leaked completion would leave the chunk unsent
            // forever.
            this.completeInitialLighting(task.chunkCoordinate);
            if (this.loadedChunkMap.get(task.chunkCoordinate) != null) {
                Pulsar.LOGGER.error("Block task for chunk ({}, {}) failed", cx, cz, t);
            } else {
                Pulsar.LOGGER.warn("Block task for chunk ({}, {}) aborted - chunk unloaded during processing", cx, cz, t);
            }
        }

        blockEngine.setStats(null);
        final long totalNs = System.nanoTime() - t0;
        if (statsOn) {
            this.stats.blockWorkerTimeNs.addAndGet(totalNs);
            this.stats.blockTasksProcessed.incrementAndGet();
        }

        if (totalNs > 100_000_000L) {
            Pulsar.LOGGER.warn(
                    "Slow block task: chunk ({},{}) total={}ms changes={}ms ({}pos, bfsInc={} bfsDec={})",
                    cx, cz, totalNs / 1_000_000L, changesNs / 1_000_000L, changesPos, changesBfsInc, changesBfsDec);
        }
    }

    /**
     * Called by each worker when it finishes initial lighting for a chunk.
     * The last worker to finish sets {@code lightReady=true} and completes
     * the pending work future.
     */
    private void completeInitialLighting(final long chunkCoordinate) {
        final ChunkLightCompletion completion;
        synchronized (this.initialLightCompletions) {
            completion = this.initialLightCompletions.get(chunkCoordinate);
        }
        if (completion == null) return;

        if (completion.remaining.decrementAndGet() <= 0) {
            synchronized (this.initialLightCompletions) {
                this.initialLightCompletions.remove(chunkCoordinate);
            }
            ((PulsarChunk) completion.chunk).pulsar$syncLightToVanilla();
            ((PulsarChunk) completion.chunk).pulsar$setLightReady(true);
            completion.future.set(null);
        }
    }

    public boolean forceRelightChunk(final int cx, final int cz) {
        final Chunk chunk = this.loadedChunkMap.get(CoordinateUtils.getChunkKey(cx, cz));
        if (chunk == null) return false;
        ((PulsarChunk) chunk).pulsar$setLightReady(false);
        final Boolean[] emptySections = PulsarEngine.getEmptySectionsForChunk(chunk);
        this.queueChunkLight(cx, cz, chunk, emptySections);
        this.scheduleUpdate();
        return true;
    }

    public void awaitPendingWork(final int cx, final int cz) {
        final ChunkLightCompletion completion;
        synchronized (this.initialLightCompletions) {
            completion = this.initialLightCompletions.get(CoordinateUtils.getChunkKey(cx, cz));
        }
        if (completion != null) {
            try {
                completion.future.get(50, TimeUnit.MILLISECONDS);
            } catch (final Exception e) {
                Pulsar.LOGGER.warn("Timed out waiting for initial light work on chunk ({}, {})", cx, cz);
            }
        }
    }

    public void shutdown() {
        this.running = false;
        this.workSignal.release();
        if (this.lightWorkerThread != null) {
            try {
                this.lightWorkerThread.join(1000);
            } catch (final InterruptedException ignored) {
            }
        }
        this.stats.close();
    }

    /**
     * Coordination object for initial chunk lighting. Both sky and block
     * workers decrement the countdown; the last one to finish sets
     * {@code lightReady} and completes the future.
     */
    static final class ChunkLightCompletion {

        final AtomicInteger remaining;
        final SettableFuture<Void> future;
        final Chunk chunk;

        ChunkLightCompletion(final int engineCount, final Chunk chunk) {
            this.remaining = new AtomicInteger(engineCount);
            this.future = SettableFuture.create();
            this.chunk = chunk;
        }
    }
}
