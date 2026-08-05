package com.sumirelabs.pulsar.light;

import com.google.common.util.concurrent.SettableFuture;
import com.sumirelabs.pulsar.Pulsar;
import com.sumirelabs.pulsar.light.engine.PulsarEngine;
import com.sumirelabs.pulsar.light.engine.ScalarBlockEngine;
import com.sumirelabs.pulsar.light.engine.ScalarSkyEngine;
import com.sumirelabs.pulsar.util.CoordinateUtils;
import com.sumirelabs.pulsar.util.SnapshotChunkMap;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import com.sumirelabs.pulsar.util.WorldUtil;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerChunkMapEntry;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Future;
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
    private final WorldHeightContext heightContext;
    private final boolean hasSkyLight;
    private final boolean hasBlockLight;

    private final ConcurrentLinkedDeque<PulsarEngine> cachedSkyPropagators;
    private final ConcurrentLinkedDeque<PulsarEngine> cachedBlockPropagators;
    private final Supplier<PulsarEngine> skyEngineFactory;
    private final Supplier<PulsarEngine> blockEngineFactory;

    private final SnapshotChunkMap loadedChunkMap = new SnapshotChunkMap();

    // Queues for sky and block light. On the server each is drained by its
    // own worker thread; on the client (thin mode) both are drained on the
    // main thread once per tick, so the engines can share storage with the
    // vanilla nibbles and mark render updates directly.
    private final LightQueue skyQueue;
    private final LightQueue blockQueue;
    private final Thread skyWorkerThread;
    private final Thread blockWorkerThread;
    private volatile boolean running = true;

    private final LightStats stats;

    // Coordination for initial chunk lighting: every active engine must finish
    // both propagation and edge reconciliation for the current generation
    // before setLightReady(true).
    private final Long2ObjectOpenHashMap<ChunkLightCompletion> initialLightCompletions = new Long2ObjectOpenHashMap<>();
    private long nextInitialLightGeneration;

    private static final int MAX_RELIGHT_ATTEMPTS = 2;
    private static final long EDGE_CHECK_BUDGET_NS = 10_000_000L; // 10ms
    private static final long BLOCK_CHANGE_BUDGET_NS = 5_000_000L; // 5ms

    public WorldLightManager(final World world, final boolean hasSkyLight, final boolean hasBlockLight) {
        this.world = world;
        this.heightContext = WorldUtil.getHeightContext(world);
        this.hasSkyLight = hasSkyLight;
        this.hasBlockLight = hasBlockLight;
        this.cachedSkyPropagators = hasSkyLight ? new ConcurrentLinkedDeque<>() : null;
        this.cachedBlockPropagators = hasBlockLight ? new ConcurrentLinkedDeque<>() : null;

        this.skyEngineFactory = hasSkyLight ? () -> new ScalarSkyEngine(world, this.heightContext) : null;
        this.blockEngineFactory = hasBlockLight ? () -> new ScalarBlockEngine(world, this.heightContext) : null;

        this.skyQueue = hasSkyLight ? new LightQueue(this.heightContext) : null;
        this.blockQueue = hasBlockLight ? new LightQueue(this.heightContext) : null;
        this.stats = new LightStats(world.isRemote);
        if (this.skyQueue != null) this.skyQueue.setStats(this.stats);
        if (this.blockQueue != null) this.blockQueue.setStats(this.stats);

        if (hasSkyLight && !world.isRemote) {
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

        if (hasBlockLight && !world.isRemote) {
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

    /**
     * True when all four horizontal neighbours are loaded and light-ready.
     * Edge checks run horizontally only, so once the four neighbours' inline
     * checks have run, this chunk's seam light is final — safe to send to
     * clients (1.12.2 has no light packet to correct a chunk afterwards).
     */
    public boolean areNeighboursLightReady(final int cx, final int cz) {
        for (int i = 0; i < 4; ++i) {
            final int nx = cx + ((i == 0) ? 1 : (i == 1) ? -1 : 0);
            final int nz = cz + ((i == 2) ? 1 : (i == 3) ? -1 : 0);
            final Chunk neighbour = this.loadedChunkMap.get(CoordinateUtils.getChunkKey(nx, nz));
            if (neighbour == null || !((PulsarChunk) neighbour).pulsar$isLightReady()) {
                return false;
            }
        }
        return true;
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

    /**
     * A section's emptiness changed (e.g. a block placed into a new EBS).
     */
    public void queueSectionChange(final int cx, final int sectionY, final int cz, final boolean empty) {
        if (this.skyQueue != null) this.skyQueue.queueSectionChange(cx, sectionY, cz, empty);
        if (this.blockQueue != null) this.blockQueue.queueSectionChange(cx, sectionY, cz, empty);
    }

    public void queueChunkLight(final int cx, final int cz, final Chunk chunk, final Boolean[] emptySections) {
        this.queueChunkLightInternal(cx, cz, chunk, emptySections);
    }

    private ChunkLightCompletion queueChunkLightInternal(final int cx, final int cz, final Chunk chunk,
                                                         final Boolean[] emptySections) {
        return this.queueChunkLightInternal(cx, cz, chunk, emptySections, 0, false);
    }

    /**
     * Internal overflow recovery: the superseded completion is chained to the
     * replacement generation so callers such as {@code /pulsar relight} still
     * observe the eventual publication and resend the chunk.
     */
    private ChunkLightCompletion queueChunkLightInternal(final int cx, final int cz, final Chunk chunk,
                                                         final Boolean[] emptySections,
                                                         final int edgeRecoveryAttempts) {
        return this.queueChunkLightInternal(
                cx, cz, chunk, emptySections, edgeRecoveryAttempts, true);
    }

    private ChunkLightCompletion queueChunkLightInternal(final int cx, final int cz, final Chunk chunk,
                                                         final Boolean[] emptySections,
                                                         final int edgeRecoveryAttempts,
                                                         final boolean handoffSupersededCompletion) {
        if (edgeRecoveryAttempts < 0 || edgeRecoveryAttempts > MAX_RELIGHT_ATTEMPTS) {
            throw new IllegalArgumentException("Invalid edge-recovery attempt: " + edgeRecoveryAttempts);
        }
        final long key = CoordinateUtils.getChunkKey(cx, cz);
        final ChunkLightCompletion completion;
        final ChunkLightCompletion superseded;

        synchronized (this.initialLightCompletions) {
            if (this.nextInitialLightGeneration == Long.MAX_VALUE) {
                throw new IllegalStateException("Initial-light generation counter exhausted");
            }
            final long generation = ++this.nextInitialLightGeneration;
            final int requiredLanes = (this.hasSkyLight ? InitialLightCompletionState.SKY : 0)
                    | (this.hasBlockLight ? InitialLightCompletionState.BLOCK : 0);
            completion = new ChunkLightCompletion(generation, requiredLanes, chunk, edgeRecoveryAttempts);
            superseded = this.initialLightCompletions.put(key, completion);

            // Queue both lanes while holding the generation lock so a newer
            // request cannot be followed by an older task insertion.
            ((PulsarChunk) chunk).pulsar$setLightReady(false);
            ((PulsarChunk) chunk).pulsar$setLightUsable(false);
            if (this.skyQueue != null) {
                this.skyQueue.queueChunkLight(cx, cz, chunk, emptySections, generation);
            }
            if (this.blockQueue != null) {
                this.blockQueue.queueChunkLight(cx, cz, chunk, emptySections, generation);
            }
        }

        if (superseded != null) {
            if (handoffSupersededCompletion) {
                completion.future.addListener(
                        () -> superseded.finish(completion.published), Runnable::run);
            } else {
                superseded.finish(false);
            }
        }
        return completion;
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
        final long key = CoordinateUtils.getChunkKey(cx, cz);
        final ChunkLightCompletion removed;
        synchronized (this.initialLightCompletions) {
            removed = this.initialLightCompletions.remove(key);
            if (this.skyQueue != null) this.skyQueue.removeChunk(cx, cz);
            if (this.blockQueue != null) this.blockQueue.removeChunk(cx, cz);
        }
        if (removed != null) {
            removed.finish(false);
        }
    }

    public boolean hasUpdates() {
        return (this.skyQueue != null && this.skyQueue.hasWork())
                || (this.blockQueue != null && this.blockQueue.hasWork());
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
        if (this.skyQueue != null) this.skyQueue.clearWorkSignal();
        if (this.blockQueue != null) this.blockQueue.clearWorkSignal();
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
        try {
            final long changeBudget = System.nanoTime() + BLOCK_CHANGE_BUDGET_NS;
            ChunkTasks task;
            while ((task = queue.removeFirstBlockChangeTask()) != null) {
                processTask(queue, task, engine, taskProcessor);
                if (System.nanoTime() > changeBudget) {
                    if (LightStats.enabled) changeBudgetYield.incrementAndGet();
                    break;
                }
            }

            boolean moreWork = true;
            while (moreWork) {
                moreWork = false;
                while ((task = queue.removeFirstInitialLightTask()) != null) {
                    processTask(queue, task, engine, taskProcessor);
                    ChunkTasks priorityTask;
                    while ((priorityTask = queue.removeFirstBlockChangeTask()) != null) {
                        processTask(queue, priorityTask, engine, taskProcessor);
                    }
                }
                final long edgeDeadline = System.nanoTime() + EDGE_CHECK_BUDGET_NS;
                while ((task = queue.removeFirstTask()) != null) {
                    processTask(queue, task, engine, taskProcessor);
                    ChunkTasks priorityTask;
                    while ((priorityTask = queue.removeFirstBlockChangeTask()) != null) {
                        processTask(queue, priorityTask, engine, taskProcessor);
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

    private static void processTask(final LightQueue queue, final ChunkTasks task,
                                    final PulsarEngine engine,
                                    final BiConsumer<ChunkTasks, PulsarEngine> taskProcessor) {
        try {
            taskProcessor.accept(task, engine);
        } finally {
            queue.completeTask(task);
        }
    }

    private void processSkyTask(final ChunkTasks task, final PulsarEngine skyEngine) {
        final boolean statsOn = LightStats.enabled;
        final long t0 = statsOn ? System.nanoTime() : 0L;
        final int cx = CoordinateUtils.getChunkX(task.chunkCoordinate);
        final int cz = CoordinateUtils.getChunkZ(task.chunkCoordinate);
        boolean finishInitial = task.initialLightChunk != null;
        boolean finishEdges = task.initialLightEdgeGeneration > 0L
                && task.queuedEdgeChecksSky != null;

        if (this.loadedChunkMap.get(task.chunkCoordinate) == null) {
            if (finishInitial) {
                this.completeInitialLighting(task, InitialLightCompletionState.SKY);
            }
            if (finishEdges) {
                this.completeEdgeLighting(task, InitialLightCompletionState.SKY);
            }
            return;
        }

        if (statsOn) {
            this.stats.chunksProcessed.incrementAndGet();
            this.stats.recordQueueLatency(task.enqueueTimeNs);
            skyEngine.setStats(this.stats);
        }

        boolean valueOverflowed = false;
        boolean edgeOverflowed = false;
        try {
            if (task.loadInitChunk != null && task.initialLightChunk == null) {
                // Persisted-light chunk: nibble/emptiness-map init only, no BFS.
                skyEngine.loadInChunk(task.loadInitChunk, task.loadInitEmptySections);
                valueOverflowed |= skyEngine.wasQueueOverflowed();
            }

            if (task.initialLightChunk != null) {
                if (statsOn) this.stats.initialLightsRun.incrementAndGet();
                skyEngine.light(task.initialLightChunk, task.initialLightEmptySections, false);
                valueOverflowed |= skyEngine.wasQueueOverflowed();
            }

            if (task.changedSectionSet != null || (task.changedPositions != null && !task.changedPositions.isEmpty())) {
                skyEngine.blocksChangedInChunk(cx, cz, task.changedPositions, task.changedSectionSet);
                valueOverflowed |= skyEngine.wasQueueOverflowed();
            }

            if (task.queuedEdgeChecksSky != null) {
                skyEngine.checkChunkEdges(cx, cz, task.queuedEdgeChecksSky);
                edgeOverflowed |= skyEngine.wasQueueOverflowed();
            }

            if (valueOverflowed) {
                if (this.requeueAfterOverflow(this.skyQueue, task, cx, cz, "Sky")) {
                    finishInitial = false;
                    finishEdges = false;
                }
            } else if (edgeOverflowed) {
                if (finishEdges) {
                    if (this.restartAfterInitialEdgeOverflow(task, cx, cz, "Sky")) {
                        finishEdges = false;
                    }
                } else if (this.requeueAfterOverflow(this.skyQueue, task, cx, cz, "Sky")) {
                    finishInitial = false;
                }
            }
        } catch (final Throwable t) {
            if (this.loadedChunkMap.get(task.chunkCoordinate) != null) {
                Pulsar.LOGGER.error("Sky task for chunk ({}, {}) failed", cx, cz, t);
            } else {
                Pulsar.LOGGER.warn("Sky task for chunk ({}, {}) aborted - chunk unloaded during processing", cx, cz, t);
            }
        }

        if (finishInitial) {
            this.completeInitialLighting(task, InitialLightCompletionState.SKY);
        }
        if (finishEdges) {
            this.completeEdgeLighting(task, InitialLightCompletionState.SKY);
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
        boolean finishInitial = task.initialLightChunk != null;
        boolean finishEdges = task.initialLightEdgeGeneration > 0L
                && task.queuedEdgeChecksBlock != null;

        if (this.loadedChunkMap.get(task.chunkCoordinate) == null) {
            if (finishInitial) {
                this.completeInitialLighting(task, InitialLightCompletionState.BLOCK);
            }
            if (finishEdges) {
                this.completeEdgeLighting(task, InitialLightCompletionState.BLOCK);
            }
            return;
        }

        if (statsOn) {
            this.stats.chunksProcessed.incrementAndGet();
            this.stats.recordQueueLatency(task.enqueueTimeNs);
            blockEngine.setStats(this.stats);
        }

        long changesNs = 0;
        int changesPos = 0, changesBfsInc = 0, changesBfsDec = 0;
        long edgesNs = 0;
        int edgeSec = 0, edgeBfsInc = 0, edgeBfsDec = 0;

        boolean valueOverflowed = false;
        boolean edgeOverflowed = false;
        try {
            if (task.loadInitChunk != null && task.initialLightChunk == null) {
                // Persisted-light chunk: nibble/emptiness-map init only, no BFS.
                blockEngine.loadInChunk(task.loadInitChunk, task.loadInitEmptySections);
                valueOverflowed |= blockEngine.wasQueueOverflowed();
            }

            if (task.initialLightChunk != null) {
                blockEngine.light(task.initialLightChunk, task.initialLightEmptySections, false);
                valueOverflowed |= blockEngine.wasQueueOverflowed();
            }

            if (task.changedSectionSet != null || (task.changedPositions != null && !task.changedPositions.isEmpty())) {
                final long t1 = System.nanoTime();
                blockEngine.blocksChangedInChunk(cx, cz, task.changedPositions, task.changedSectionSet);
                changesNs = System.nanoTime() - t1;
                changesPos = blockEngine.lastPositionsProcessed;
                changesBfsInc = blockEngine.lastBfsIncreaseTotal;
                changesBfsDec = blockEngine.lastBfsDecreaseTotal;
                if (statsOn) this.stats.blockPositionsProcessed.addAndGet(changesPos);
                valueOverflowed |= blockEngine.wasQueueOverflowed();
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
                edgeOverflowed |= blockEngine.wasQueueOverflowed();
            }

            if (valueOverflowed) {
                if (this.requeueAfterOverflow(this.blockQueue, task, cx, cz, "Block")) {
                    finishInitial = false;
                    finishEdges = false;
                }
            } else if (edgeOverflowed) {
                if (finishEdges) {
                    if (this.restartAfterInitialEdgeOverflow(task, cx, cz, "Block")) {
                        finishEdges = false;
                    }
                } else if (this.requeueAfterOverflow(this.blockQueue, task, cx, cz, "Block")) {
                    finishInitial = false;
                }
            }
        } catch (final Throwable t) {
            if (this.loadedChunkMap.get(task.chunkCoordinate) != null) {
                Pulsar.LOGGER.error("Block task for chunk ({}, {}) failed", cx, cz, t);
            } else {
                Pulsar.LOGGER.warn("Block task for chunk ({}, {}) aborted - chunk unloaded during processing", cx, cz, t);
            }
        }

        if (finishInitial) {
            this.completeInitialLighting(task, InitialLightCompletionState.BLOCK);
        }
        if (finishEdges) {
            this.completeEdgeLighting(task, InitialLightCompletionState.BLOCK);
        }

        blockEngine.setStats(null);
        final long totalNs = System.nanoTime() - t0;
        if (statsOn) {
            this.stats.blockWorkerTimeNs.addAndGet(totalNs);
            this.stats.blockTasksProcessed.incrementAndGet();
        }

        if (totalNs > 100_000_000L) {
            Pulsar.LOGGER.warn(
                    "Slow block task: chunk ({},{}) total={}ms changes={}ms ({}pos, bfsInc={} bfsDec={}) edges={}ms ({}sec, bfsInc={} bfsDec={})",
                    cx, cz, totalNs / 1_000_000L, changesNs / 1_000_000L, changesPos, changesBfsInc, changesBfsDec,
                    edgesNs / 1_000_000L, edgeSec, edgeBfsInc, edgeBfsDec);
        }
    }

    /**
     * Requeue a full relight after any operation in a task overflowed its BFS
     * queue. Coordinated relights retain their generation and defer completion
     * until the final attempt; ordinary update batches are promoted to a new
     * coordinated relight. A newer queued generation supersedes an older retry.
     */
    private boolean requeueAfterOverflow(final LightQueue queue, final ChunkTasks task,
                                         final int cx, final int cz, final String engineName) {
        if (task.relightAttempts < MAX_RELIGHT_ATTEMPTS) {
            final Chunk chunk = this.loadedChunkMap.get(task.chunkCoordinate);
            if (chunk == null) {
                return false;
            }
            final Boolean[] emptySections = PulsarEngine.getEmptySectionsForChunk(chunk);
            if (task.initialLightGeneration <= 0L) {
                // An ordinary block/section/edge task has no completion state
                // to release. Promote its recovery to a coordinated relight
                // of every active lane so final sync and edge checks run.
                final int edgeRecoveryAttempts = task.initialLightEdgeGeneration > 0L
                        ? Math.min(MAX_RELIGHT_ATTEMPTS, task.edgeCheckAttempts + 1) : 0;
                this.queueChunkLightInternal(cx, cz, chunk, emptySections, edgeRecoveryAttempts);
                this.scheduleUpdate();
                return true;
            }

            queue.requeueChunkLight(cx, cz, chunk, emptySections,
                    task.initialLightGeneration, task.relightAttempts);
            // A false return means a newer generation is already queued. It
            // still supersedes this attempt, so the old generation must not
            // report completion.
            return true;
        }

        Pulsar.LOGGER.error("{} engine: chunk ({}, {}) overflowed BFS queue {} times - giving up.",
                engineName, cx, cz, task.relightAttempts + 1);
        return false;
    }

    /**
     * Restart coordinated lighting after final edge reconciliation overflows.
     * An edge-only retry cannot recover updates dropped after propagation has
     * already moved away from the seam, so a fresh generation reruns both
     * initial lanes. The edge recovery count crosses generations to keep the
     * retry bound finite.
     */
    private boolean restartAfterInitialEdgeOverflow(final ChunkTasks task, final int cx, final int cz,
                                                    final String engineName) {
        if (task.initialLightEdgeGeneration <= 0L) {
            return false;
        }
        if (task.edgeCheckAttempts < MAX_RELIGHT_ATTEMPTS) {
            synchronized (this.initialLightCompletions) {
                final ChunkLightCompletion completion = this.initialLightCompletions.get(task.chunkCoordinate);
                if (completion == null || completion.generation != task.initialLightEdgeGeneration) {
                    // A newer generation already superseded this edge pass.
                    return true;
                }
                if (this.loadedChunkMap.get(task.chunkCoordinate) != completion.chunk) {
                    return false;
                }
                final Boolean[] emptySections = PulsarEngine.getEmptySectionsForChunk(completion.chunk);
                this.queueChunkLightInternal(
                        cx, cz, completion.chunk, emptySections, task.edgeCheckAttempts + 1);
                this.scheduleUpdate();
                return true;
            }
        } else {
            Pulsar.LOGGER.error("{} engine: chunk ({}, {}) overflowed final edge reconciliation {} times - giving up.",
                    engineName, cx, cz, task.edgeCheckAttempts + 1);
            return false;
        }
    }

    /**
     * Called once by a lane after its final initial-propagation attempt. Stale
     * generations and duplicate lane completions are ignored. Once every
     * required lane finishes, the chunk becomes usable by the light engines
     * and a generation-tagged full edge pass is queued for each active lane.
     * The public light-ready flag and completion future remain pending.
     */
    private void completeInitialLighting(final ChunkTasks task, final int lane) {
        if (task.initialLightGeneration <= 0L) {
            return;
        }

        final long chunkCoordinate = task.chunkCoordinate;
        ChunkLightCompletion failedCompletion = null;
        Throwable transitionFailure = null;

        synchronized (this.initialLightCompletions) {
            final ChunkLightCompletion completion = this.initialLightCompletions.get(chunkCoordinate);
            if (completion == null) {
                return;
            }
            final InitialLightCompletionState.Result result =
                    completion.state.completeInitial(task.initialLightGeneration, lane);
            if (result != InitialLightCompletionState.Result.INITIAL_COMPLETE) {
                return;
            }

            final PulsarChunk pulsarChunk = (PulsarChunk) completion.chunk;
            if (this.loadedChunkMap.get(chunkCoordinate) != completion.chunk) {
                this.initialLightCompletions.remove(chunkCoordinate);
                pulsarChunk.pulsar$setLightReady(false);
                pulsarChunk.pulsar$setLightUsable(false);
                failedCompletion = completion;
            } else {
                try {
                    // Edge-pending chunks may be used by both engines, but
                    // cannot be saved or sent until BOTH edge lanes finish.
                    pulsarChunk.pulsar$setLightUsable(true);
                    final int cx = CoordinateUtils.getChunkX(chunkCoordinate);
                    final int cz = CoordinateUtils.getChunkZ(chunkCoordinate);
                    final boolean skyQueued = this.skyQueue == null
                            || this.skyQueue.queueInitialLightEdgeCheckAllSections(
                            cx, cz, true, completion.generation, completion.edgeRecoveryAttempts);
                    final boolean blockQueued = this.blockQueue == null
                            || this.blockQueue.queueInitialLightEdgeCheckAllSections(
                            cx, cz, false, completion.generation, completion.edgeRecoveryAttempts);
                    if (!skyQueued || !blockQueued) {
                        throw new IllegalStateException("A newer light task occupied the edge queue");
                    }
                } catch (final Throwable t) {
                    this.initialLightCompletions.remove(chunkCoordinate);
                    try {
                        pulsarChunk.pulsar$setLightReady(false);
                        pulsarChunk.pulsar$setLightUsable(false);
                    } catch (final Throwable suppressed) {
                        t.addSuppressed(suppressed);
                    }
                    failedCompletion = completion;
                    transitionFailure = t;
                }
            }
        }

        if (failedCompletion != null) {
            failedCompletion.finish(false);
        }
        if (transitionFailure != null) {
            Pulsar.LOGGER.error("Failed to start edge reconciliation for chunk ({}, {})",
                    CoordinateUtils.getChunkX(chunkCoordinate), CoordinateUtils.getChunkZ(chunkCoordinate),
                    transitionFailure);
        }
    }

    /**
     * Called after a generation-tagged edge pass finishes. Only the last
     * required lane may copy the reconciled SWMR nibbles to vanilla storage,
     * publish light-ready and release waiters.
     */
    private void completeEdgeLighting(final ChunkTasks task, final int lane) {
        if (task.initialLightEdgeGeneration <= 0L) {
            return;
        }

        final long chunkCoordinate = task.chunkCoordinate;
        final ChunkLightCompletion completion;

        synchronized (this.initialLightCompletions) {
            completion = this.initialLightCompletions.get(chunkCoordinate);
            if (completion == null) {
                return;
            }
            final InitialLightCompletionState.Result result =
                    completion.state.completeEdges(task.initialLightEdgeGeneration, lane);
            if (result != InitialLightCompletionState.Result.COMPLETE) {
                return;
            }
        }

        boolean published = false;
        Throwable publishFailure = null;
        // Serialise publication only against other generations of THIS chunk,
        // not against every chunk finishing in the world. The generation is
        // checked both before and after the bulk nibble copy so an intervening
        // relight can supersede this one without stale lightReady publication.
        synchronized (completion.chunk) {
            boolean currentGeneration;
            synchronized (this.initialLightCompletions) {
                currentGeneration = this.initialLightCompletions.get(chunkCoordinate) == completion
                        && this.loadedChunkMap.get(chunkCoordinate) == completion.chunk;
            }

            try {
                if (currentGeneration) {
                    ((PulsarChunk) completion.chunk).pulsar$syncLightToVanilla();
                }
            } catch (final Throwable t) {
                publishFailure = t;
            }

            synchronized (this.initialLightCompletions) {
                if (this.initialLightCompletions.get(chunkCoordinate) == completion) {
                    this.initialLightCompletions.remove(chunkCoordinate);
                    currentGeneration = this.loadedChunkMap.get(chunkCoordinate) == completion.chunk;
                    try {
                        if (currentGeneration && publishFailure == null) {
                            ((PulsarChunk) completion.chunk).pulsar$setLightReady(true);
                            published = true;
                        } else if (currentGeneration) {
                            ((PulsarChunk) completion.chunk).pulsar$setLightReady(false);
                            ((PulsarChunk) completion.chunk).pulsar$setLightUsable(false);
                        }
                    } catch (final Throwable t) {
                        if (publishFailure == null) {
                            publishFailure = t;
                        } else {
                            publishFailure.addSuppressed(t);
                        }
                        try {
                            ((PulsarChunk) completion.chunk).pulsar$setLightReady(false);
                            ((PulsarChunk) completion.chunk).pulsar$setLightUsable(false);
                        } catch (final Throwable suppressed) {
                            publishFailure.addSuppressed(suppressed);
                        }
                        published = false;
                    }
                }
            }
        }

        completion.finish(published);
        if (publishFailure != null) {
            Pulsar.LOGGER.error("Failed to publish edge-reconciled light for chunk ({}, {})",
                    CoordinateUtils.getChunkX(chunkCoordinate), CoordinateUtils.getChunkZ(chunkCoordinate),
                    publishFailure);
        }
    }

    public boolean forceRelightChunk(final int cx, final int cz) {
        final long key = CoordinateUtils.getChunkKey(cx, cz);
        final Chunk chunk = this.loadedChunkMap.get(key);
        if (chunk == null) return false;
        final Boolean[] emptySections = PulsarEngine.getEmptySectionsForChunk(chunk);
        final ChunkLightCompletion completion = this.queueChunkLightInternal(cx, cz, chunk, emptySections);
        this.scheduleUpdate();

        // 1.12.2 has no light-update packet, so a relight is invisible to
        // clients that already hold the chunk — resend it once propagation
        // and edge reconciliation complete. Packet construction must happen
        // on the server thread.
        if (!this.world.isRemote) {
            completion.future.addListener(() -> {
                if (!completion.published) return;
                final MinecraftServer server = this.world.getMinecraftServer();
                if (server == null) return;
                server.addScheduledTask(() -> {
                    if (!(this.world instanceof WorldServer)) return;
                    final PlayerChunkMapEntry entry =
                            ((WorldServer) this.world).getPlayerChunkMap().getEntry(cx, cz);
                    final Chunk current = this.loadedChunkMap.get(key);
                    if (entry != null && current == completion.chunk
                            && ((PulsarChunk) current).pulsar$isLightReady()) {
                        entry.sendPacket(new SPacketChunkData(
                                current, this.heightContext.getFullChunkSectionMask()));
                    }
                });
            }, Runnable::run);
        }
        return true;
    }

    /**
     * True while queued work could still change this chunk's light values
     * (pending initial light, block/section changes, or an unfinished
     * propagation/edge completion latch). Ordinary edge-check-only tasks do
     * not count. Used to decide whether the current SWMR data is safe to
     * persist as valid.
     */
    public boolean hasPendingLightWork(final int cx, final int cz) {
        final long key = CoordinateUtils.getChunkKey(cx, cz);
        synchronized (this.initialLightCompletions) {
            if (this.initialLightCompletions.containsKey(key)) {
                return true;
            }
        }
        return (this.skyQueue != null && this.skyQueue.hasPendingLightWork(key))
                || (this.blockQueue != null && this.blockQueue.hasPendingLightWork(key));
    }

    /**
     * Wait briefly for all queued or in-flight work touching a chunk. Returns
     * {@code false} on timeout/interruption so unload can invalidate the saved
     * light instead of serialising data while a worker may still mutate it.
     */
    public boolean awaitPendingWork(final int cx, final int cz) {
        final long key = CoordinateUtils.getChunkKey(cx, cz);
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50L);

        while (true) {
            final Future<Void> pending = this.getPendingWorkFuture(key);
            if (pending == null) {
                return true;
            }
            final long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                break;
            }
            try {
                pending.get(remaining, TimeUnit.NANOSECONDS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                Pulsar.LOGGER.warn("Interrupted while waiting for light work on chunk ({}, {})", cx, cz);
                return false;
            } catch (final Exception e) {
                break;
            }
        }

        Pulsar.LOGGER.warn("Timed out waiting for light work on chunk ({}, {})", cx, cz);
        return false;
    }

    private Future<Void> getPendingWorkFuture(final long key) {
        Future<Void> future = this.skyQueue == null ? null : this.skyQueue.getPendingWorkFuture(key);
        if (future != null) {
            return future;
        }
        future = this.blockQueue == null ? null : this.blockQueue.getPendingWorkFuture(key);
        if (future != null) {
            return future;
        }
        synchronized (this.initialLightCompletions) {
            final ChunkLightCompletion completion = this.initialLightCompletions.get(key);
            return completion == null ? null : completion.future;
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

    /** Coordination object for one generation of a chunk's initial light. */
    static final class ChunkLightCompletion {

        final long generation;
        final int edgeRecoveryAttempts;
        final InitialLightCompletionState state;
        final SettableFuture<Void> future;
        final Chunk chunk;
        volatile boolean published;

        ChunkLightCompletion(final long generation, final int requiredLanes, final Chunk chunk,
                             final int edgeRecoveryAttempts) {
            this.generation = generation;
            this.edgeRecoveryAttempts = edgeRecoveryAttempts;
            this.state = new InitialLightCompletionState(generation, requiredLanes);
            this.future = SettableFuture.create();
            this.chunk = chunk;
        }

        synchronized void finish(final boolean published) {
            if (this.future.isDone()) {
                return;
            }
            this.published = published;
            this.future.set(null);
        }
    }
}
