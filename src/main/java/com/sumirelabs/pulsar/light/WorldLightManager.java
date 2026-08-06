package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.Pulsar;
import com.sumirelabs.pulsar.light.engine.PulsarEngine;
import com.sumirelabs.pulsar.light.engine.ScalarBlockEngine;
import com.sumirelabs.pulsar.light.engine.ScalarSkyEngine;
import com.sumirelabs.pulsar.util.CoordinateUtils;
import com.sumirelabs.pulsar.util.SnapshotChunkMap;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import com.sumirelabs.pulsar.util.WorldUtil;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerChunkMapEntry;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Per-{@link World} light manager. Owns the worker threads, engine pools and
 * task queues that drive Pulsar's BFS lighting. Ported from SuperNova
 * {@code WorldLightManager} (1.7.10), with the RGB engine factories removed
 * and the API surface simplified for scalar mode only.
 */
public final class WorldLightManager {

    private final World world;
    private final WorldHeightContext heightContext;

    private final SnapshotChunkMap loadedChunkMap = new SnapshotChunkMap();

    // Queues for sky and block light. On the server each is drained by its
    // own worker thread; on the client (thin mode) both are drained on the
    // main thread once per tick, so the engines can share storage with the
    // vanilla nibbles and mark render updates directly.
    private final LightQueue skyQueue;
    private final LightQueue blockQueue;
    private final InitialLightCoordinator initialLighting;
    private final LightEngineWorker skyWorker;
    private final LightEngineWorker blockWorker;

    private final LightStats stats;

    public WorldLightManager(final World world, final boolean hasSkyLight, final boolean hasBlockLight) {
        this.world = world;
        this.heightContext = WorldUtil.getHeightContext(world);
        this.skyQueue = hasSkyLight ? new LightQueue(this.heightContext) : null;
        this.blockQueue = hasBlockLight ? new LightQueue(this.heightContext) : null;
        this.stats = new LightStats(world.isRemote);
        if (this.skyQueue != null) this.skyQueue.setStats(this.stats);
        if (this.blockQueue != null) this.blockQueue.setStats(this.stats);
        this.initialLighting = new InitialLightCoordinator(
                this.loadedChunkMap, this.skyQueue, this.blockQueue, this::scheduleUpdate);
        this.skyWorker = hasSkyLight ? new LightEngineWorker(
                this.skyQueue,
                () -> new ScalarSkyEngine(world, this.heightContext),
                this::processSkyTask,
                this.stats.skyChangeBudgetYields,
                this.stats.edgeBudgetYields,
                "propagateSkyChanges",
                "Pulsar-Sky",
                !world.isRemote) : null;
        this.blockWorker = hasBlockLight ? new LightEngineWorker(
                this.blockQueue,
                () -> new ScalarBlockEngine(world, this.heightContext),
                this::processBlockTask,
                this.stats.blockChangeBudgetYields,
                this.stats.edgeBudgetYields,
                "propagateBlockChanges",
                "Pulsar-Block",
                !world.isRemote) : null;
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
        this.initialLighting.queue(cx, cz, chunk, emptySections);
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
        this.initialLighting.removeChunk(cx, cz);
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
        if (this.skyWorker != null) this.skyWorker.processPending();
        if (this.blockWorker != null) this.blockWorker.processPending();
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
                this.initialLighting.completeInitial(task, InitialLightCompletionState.SKY);
            }
            if (finishEdges) {
                this.initialLighting.completeEdges(task, InitialLightCompletionState.SKY);
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
                    if (this.initialLighting.restartAfterEdgeOverflow(task, cx, cz, "Sky")) {
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
            this.initialLighting.completeInitial(task, InitialLightCompletionState.SKY);
        }
        if (finishEdges) {
            this.initialLighting.completeEdges(task, InitialLightCompletionState.SKY);
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
                this.initialLighting.completeInitial(task, InitialLightCompletionState.BLOCK);
            }
            if (finishEdges) {
                this.initialLighting.completeEdges(task, InitialLightCompletionState.BLOCK);
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
                    if (this.initialLighting.restartAfterEdgeOverflow(task, cx, cz, "Block")) {
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
            this.initialLighting.completeInitial(task, InitialLightCompletionState.BLOCK);
        }
        if (finishEdges) {
            this.initialLighting.completeEdges(task, InitialLightCompletionState.BLOCK);
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
        if (task.relightAttempts < InitialLightCoordinator.MAX_RELIGHT_ATTEMPTS) {
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
                        ? Math.min(InitialLightCoordinator.MAX_RELIGHT_ATTEMPTS, task.edgeCheckAttempts + 1) : 0;
                this.initialLighting.queueRecovery(cx, cz, chunk, emptySections, edgeRecoveryAttempts);
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

    public boolean forceRelightChunk(final int cx, final int cz) {
        final long key = CoordinateUtils.getChunkKey(cx, cz);
        final Chunk chunk = this.loadedChunkMap.get(key);
        if (chunk == null) return false;
        final Boolean[] emptySections = PulsarEngine.getEmptySectionsForChunk(chunk);
        final ChunkLightCompletion completion = this.initialLighting.queue(cx, cz, chunk, emptySections);
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
        if (this.initialLighting.hasPending(key)) {
            return true;
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
        return this.initialLighting.getPendingFuture(key);
    }

    public void shutdown() {
        if (this.skyWorker != null) this.skyWorker.requestStop();
        if (this.blockWorker != null) this.blockWorker.requestStop();
        if (this.skyWorker != null) this.skyWorker.awaitStop();
        if (this.blockWorker != null) this.blockWorker.awaitStop();
        this.stats.close();
    }

}
