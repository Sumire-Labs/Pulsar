package com.sumirelabs.pulsar.light;

import com.google.common.util.concurrent.SettableFuture;
import com.sumirelabs.pulsar.Pulsar;
import com.sumirelabs.pulsar.light.engine.PulsarEngine;
import com.sumirelabs.pulsar.light.engine.ScalarBlockEngine;
import com.sumirelabs.pulsar.light.engine.ScalarSkyEngine;
import com.sumirelabs.pulsar.util.CoordinateUtils;
import com.sumirelabs.pulsar.util.SnapshotChunkMap;
import com.sumirelabs.pulsar.util.WorldUtil;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import java.util.concurrent.ConcurrentLinkedDeque;
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

    // Separate worker threads + queues for sky and block light
    private final LightQueue skyQueue;
    private final LightQueue blockQueue;
    private final Thread skyWorkerThread;
    private final Thread blockWorkerThread;
    private volatile boolean running = true;

    private final LightStats stats;

    // Client-only: render update coordinates queued by worker threads for main-thread drain.
    // Each long packs (cx << 32) | ((cz & 0xFFFF) << 16) | (cy & 0xFFFF).
    private final RenderUpdateQueue pendingRenderUpdates = new RenderUpdateQueue(4096);

    // Coordination for initial chunk lighting: both engines must finish before setLightReady(true).
    private final Long2ObjectOpenHashMap<ChunkLightCompletion> initialLightCompletions = new Long2ObjectOpenHashMap<>();

    private static final int MAX_RELIGHT_ATTEMPTS = 2;
    private static final long EDGE_CHECK_BUDGET_NS = 10_000_000L; // 10ms
    private static final long BLOCK_CHANGE_BUDGET_NS = 5_000_000L; // 5ms

    public WorldLightManager(final World world, final boolean hasSkyLight, final boolean hasBlockLight) {
        this.world = world;
        this.hasSkyLight = hasSkyLight;
        this.hasBlockLight = hasBlockLight;
        this.cachedSkyPropagators = hasSkyLight ? new ConcurrentLinkedDeque<>() : null;
        this.cachedBlockPropagators = hasBlockLight ? new ConcurrentLinkedDeque<>() : null;

        this.skyEngineFactory = hasSkyLight ? () -> new ScalarSkyEngine(world) : null;
        this.blockEngineFactory = hasBlockLight ? () -> new ScalarBlockEngine(world) : null;

        this.skyQueue = hasSkyLight ? new LightQueue() : null;
        this.blockQueue = hasBlockLight ? new LightQueue() : null;
        this.stats = new LightStats(world.isRemote);
        if (this.skyQueue != null) this.skyQueue.setStats(this.stats);
        if (this.blockQueue != null) this.blockQueue.setStats(this.stats);

        if (hasSkyLight) {
            this.skyWorkerThread = new Thread(
                    () -> {
                        while (this.running) {
                            if (this.skyQueue.isEmpty()) {
                                try {
                                    this.skyQueue.waitForWork();
                                } catch (final InterruptedException e) {
                                    break;
                                }
                            }
                            this.propagateSkyChanges();
                        }
                    }, "Pulsar-Sky");
            this.skyWorkerThread.setDaemon(true);
            this.skyWorkerThread.start();
        } else {
            this.skyWorkerThread = null;
        }

        if (hasBlockLight) {
            this.blockWorkerThread = new Thread(
                    () -> {
                        while (this.running) {
                            if (this.blockQueue.isEmpty()) {
                                try {
                                    this.blockQueue.waitForWork();
                                } catch (final InterruptedException e) {
                                    break;
                                }
                            }
                            this.propagateBlockChanges();
                        }
                    }, "Pulsar-Block");
            this.blockWorkerThread.setDaemon(true);
            this.blockWorkerThread.start();
        } else {
            this.blockWorkerThread = null;
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
        engine.suppressRenderNotify = false;
        engine.pendingRenderTarget = null;
        if (cache.size() < 4) {
            cache.addFirst(engine);
        }
    }

    private PulsarEngine getSkyLightEngine() {
        return getEngine(this.cachedSkyPropagators, this.skyEngineFactory);
    }

    private PulsarEngine getBlockLightEngine() {
        return getEngine(this.cachedBlockPropagators, this.blockEngineFactory);
    }

    private void releaseSkyLightEngine(final PulsarEngine engine) {
        releaseEngine(this.cachedSkyPropagators, engine);
    }

    private void releaseBlockLightEngine(final PulsarEngine engine) {
        releaseEngine(this.cachedBlockPropagators, engine);
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

    public void processClientRenderUpdates() {
        final long startNs = System.nanoTime();
        final int count = this.pendingRenderUpdates.drain(v -> {
            final int bx = (int) (v >> 32) << 4;
            final int bz = (short) ((v >> 16) & 0xFFFF) << 4;
            final int by = (short) (v & 0xFFFF) << 4;
            this.world.markBlockRangeForRenderUpdate(
                    new BlockPos(bx, by, bz),
                    new BlockPos(bx + 15, by + 15, bz + 15));
        });
        if (count > 0) {
            this.stats.drainedSections += count;
            this.stats.drainTimeNs += System.nanoTime() - startNs;
        }
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
        this.propagateChanges(this.skyQueue, this.cachedSkyPropagators, this.skyEngineFactory,
                this::processSkyTask, this.stats.skyChangeBudgetYields, "propagateSkyChanges");
    }

    private void propagateBlockChanges() {
        this.propagateChanges(this.blockQueue, this.cachedBlockPropagators, this.blockEngineFactory,
                this::processBlockTask, this.stats.blockChangeBudgetYields, "propagateBlockChanges");
    }

    private void propagateChanges(final LightQueue queue, final ConcurrentLinkedDeque<PulsarEngine> cache,
                                  final Supplier<PulsarEngine> factory,
                                  final BiConsumer<ChunkTasks, PulsarEngine> taskProcessor,
                                  final AtomicInteger changeBudgetYield, final String label) {
        final PulsarEngine engine = getEngine(cache, factory);
        if (engine == null) return;
        if (this.world.isRemote) {
            engine.suppressRenderNotify = true;
            engine.pendingRenderTarget = this.pendingRenderUpdates;
        }
        try {
            final long changeBudget = System.nanoTime() + BLOCK_CHANGE_BUDGET_NS;
            ChunkTasks task;
            while ((task = queue.removeFirstBlockChangeTask()) != null) {
                taskProcessor.accept(task, engine);
                if (System.nanoTime() > changeBudget) {
                    changeBudgetYield.incrementAndGet();
                    break;
                }
            }

            boolean moreWork = true;
            while (moreWork) {
                moreWork = false;
                while ((task = queue.removeFirstInitialLightTask()) != null) {
                    taskProcessor.accept(task, engine);
                    ChunkTasks priorityTask;
                    while ((priorityTask = queue.removeFirstBlockChangeTask()) != null) {
                        taskProcessor.accept(priorityTask, engine);
                    }
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
                        this.stats.edgeBudgetYields.incrementAndGet();
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
        final long t0 = System.nanoTime();
        final int cx = CoordinateUtils.getChunkX(task.chunkCoordinate);
        final int cz = CoordinateUtils.getChunkZ(task.chunkCoordinate);

        if (this.loadedChunkMap.get(task.chunkCoordinate) == null) {
            this.completeInitialLighting(task.chunkCoordinate);
            return;
        }

        this.stats.chunksProcessed.incrementAndGet();
        this.stats.recordQueueLatency(task.enqueueTimeNs);
        skyEngine.setStats(this.stats);

        try {
            if (task.initialLightChunk != null) {
                this.stats.initialLightsRun.incrementAndGet();
                skyEngine.light(task.initialLightChunk, task.initialLightEmptySections, false);
                this.completeInitialLighting(task.chunkCoordinate);

                this.skyQueue.queueEdgeCheckAllSections(cx, cz, true);
            }

            if (task.changedSectionSet != null || (task.changedPositions != null && !task.changedPositions.isEmpty())) {
                skyEngine.blocksChangedInChunk(cx, cz, task.changedPositions, task.changedSectionSet);
            }

            if (task.queuedEdgeChecksSky != null) {
                skyEngine.checkChunkEdges(cx, cz, task.queuedEdgeChecksSky);
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
        } catch (final NullPointerException e) {
            this.completeInitialLighting(task.chunkCoordinate);
            if (this.loadedChunkMap.get(task.chunkCoordinate) != null) {
                throw new RuntimeException("Unexpected NPE processing sky task for chunk (" + cx + ", " + cz + ")", e);
            }
            Pulsar.LOGGER.warn("Sky task for chunk ({}, {}) aborted - chunk unloaded during processing", cx, cz, e);
        }

        skyEngine.setStats(null);
        this.stats.skyWorkerTimeNs.addAndGet(System.nanoTime() - t0);
        this.stats.skyTasksProcessed.incrementAndGet();
    }

    private void processBlockTask(final ChunkTasks task, final PulsarEngine blockEngine) {
        final long t0 = System.nanoTime();
        final int cx = CoordinateUtils.getChunkX(task.chunkCoordinate);
        final int cz = CoordinateUtils.getChunkZ(task.chunkCoordinate);

        if (this.loadedChunkMap.get(task.chunkCoordinate) == null) {
            this.completeInitialLighting(task.chunkCoordinate);
            return;
        }

        this.stats.chunksProcessed.incrementAndGet();
        this.stats.recordQueueLatency(task.enqueueTimeNs);
        blockEngine.setStats(this.stats);

        long changesNs = 0;
        int changesPos = 0, changesBfsInc = 0, changesBfsDec = 0;
        long edgesNs = 0;
        int edgeSec = 0, edgeBfsInc = 0, edgeBfsDec = 0;

        try {
            if (task.initialLightChunk != null) {
                blockEngine.light(task.initialLightChunk, task.initialLightEmptySections, false);

                if (this.world.isRemote) {
                    final PulsarChunk ext = (PulsarChunk) task.initialLightChunk;
                    final SWMRNibbleArray[] nibs = ext.pulsar$getBlockNibbles();
                    if (nibs != null) {
                        for (int i = 0; i < nibs.length; i++) {
                            final SWMRNibbleArray r = nibs[i];
                            if (r == null || r.isNullNibbleUpdating() || r.isUninitialisedUpdating()) continue;
                            final int sectionY = i + WorldUtil.getMinLightSection();
                            this.pendingRenderUpdates.offer(((long) cx << 32) | ((long) (cz & 0xFFFF) << 16) | (sectionY & 0xFFFFL));
                        }
                    }
                }

                this.completeInitialLighting(task.chunkCoordinate);
                this.blockQueue.queueEdgeCheckAllSections(cx, cz, false);
            }

            if (task.changedSectionSet != null || (task.changedPositions != null && !task.changedPositions.isEmpty())) {
                final long t1 = System.nanoTime();
                blockEngine.blocksChangedInChunk(cx, cz, task.changedPositions, task.changedSectionSet);
                changesNs = System.nanoTime() - t1;
                changesPos = blockEngine.lastPositionsProcessed;
                changesBfsInc = blockEngine.lastBfsIncreaseTotal;
                changesBfsDec = blockEngine.lastBfsDecreaseTotal;
                this.stats.blockPositionsProcessed.addAndGet(changesPos);
            }

            if (task.queuedEdgeChecksBlock != null) {
                blockEngine.lastBfsIncreaseTotal = 0;
                blockEngine.lastBfsDecreaseTotal = 0;
                edgeSec = task.queuedEdgeChecksBlock.size();
                final long t2 = System.nanoTime();
                blockEngine.checkChunkEdges(cx, cz, task.queuedEdgeChecksBlock);
                edgesNs = System.nanoTime() - t2;
                edgeBfsInc = blockEngine.lastBfsIncreaseTotal;
                edgeBfsDec = blockEngine.lastBfsDecreaseTotal;
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
        } catch (final NullPointerException e) {
            this.completeInitialLighting(task.chunkCoordinate);
            if (this.loadedChunkMap.get(task.chunkCoordinate) != null) {
                throw new RuntimeException("Unexpected NPE processing block task for chunk (" + cx + ", " + cz + ")", e);
            }
            Pulsar.LOGGER.warn("Block task for chunk ({}, {}) aborted - chunk unloaded during processing", cx, cz, e);
        }

        blockEngine.setStats(null);
        final long totalNs = System.nanoTime() - t0;
        this.stats.blockWorkerTimeNs.addAndGet(totalNs);
        this.stats.blockTasksProcessed.incrementAndGet();

        if (totalNs > 100_000_000L) {
            Pulsar.LOGGER.warn(
                    "Slow block task: chunk ({},{}) total={}ms changes={}ms ({}pos, bfsInc={} bfsDec={}) edges={}ms ({}sec, bfsInc={} bfsDec={})",
                    cx, cz, totalNs / 1_000_000L, changesNs / 1_000_000L, changesPos, changesBfsInc, changesBfsDec,
                    edgesNs / 1_000_000L, edgeSec, edgeBfsInc, edgeBfsDec);
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

    /**
     * Synchronous block change — runs BFS on the calling thread. Used for
     * player-initiated block place/break on the client so lighting updates
     * are visually instant.
     */
    public void blockChange(final int x, final int y, final int z) {
        if (y < WorldUtil.getMinBlockY() || y > WorldUtil.getMaxBlockY()) return;
        final PulsarEngine skyEngine = this.getSkyLightEngine();
        final PulsarEngine blockEngine = this.getBlockLightEngine();
        try {
            if (skyEngine != null) {
                skyEngine.blockChanged(x, y, z);
                if (skyEngine.wasQueueOverflowed()) requeueChunkFromSync(x >> 4, z >> 4);
            }
            if (blockEngine != null) {
                blockEngine.blockChanged(x, y, z);
                if (blockEngine.wasQueueOverflowed()) requeueChunkFromSync(x >> 4, z >> 4);
            }
        } finally {
            this.releaseSkyLightEngine(skyEngine);
            this.releaseBlockLightEngine(blockEngine);
        }
        this.queueBlockChange(x, y, z);
        this.scheduleUpdate();
    }

    private void requeueChunkFromSync(final int cx, final int cz) {
        final Chunk chunk = this.loadedChunkMap.get(CoordinateUtils.getChunkKey(cx, cz));
        if (chunk == null) return;
        final Boolean[] emptySections = PulsarEngine.getEmptySectionsForChunk(chunk);
        if (this.skyQueue != null) this.skyQueue.requeueChunkLight(cx, cz, chunk, emptySections, 0);
        if (this.blockQueue != null) this.blockQueue.requeueChunkLight(cx, cz, chunk, emptySections, 0);
        this.scheduleUpdate();
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
        if (this.skyQueue != null) this.skyQueue.wakeUp();
        if (this.blockQueue != null) this.blockQueue.wakeUp();
        if (this.skyWorkerThread != null) {
            try {
                this.skyWorkerThread.join(1000);
            } catch (final InterruptedException ignored) {
            }
        }
        if (this.blockWorkerThread != null) {
            try {
                this.blockWorkerThread.join(1000);
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
