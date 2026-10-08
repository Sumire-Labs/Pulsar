package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.Pulsar;
import com.sumirelabs.pulsar.config.PulsarConfig;
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
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collection;

/**
 * Per-{@link World} light manager. Owns the worker threads, engine pools and
 * task queues that drive Pulsar's BFS lighting. Ported from SuperNova
 * {@code WorldLightManager} (1.7.10), with the RGB engine factories removed
 * and the API surface simplified for scalar mode only.
 */
public final class WorldLightManager {
    // Manual range relights are uncommon and may reserve a large region. Keep
    // them off the tick thread and serialize them against normal jobs per world.
    private static final ExecutorService RANGE_RELIGHTS = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Pulsar-RangeRelight");
        thread.setDaemon(true);
        return thread;
    });
    private final ReentrantReadWriteLock lightingGate = new ReentrantReadWriteLock(true);

    private boolean blockFirstClientTick;
    private final ClientRenderUpdates<Chunk> clientRenderUpdates = new ClientRenderUpdates<>();

    /**
     * Dense asynchronous edits can span several chunk tasks. Processing each
     * chunk's skylight decrease independently lets a still-stale neighbour
     * re-seed the columns just cleared by the previous task. Rebuild dense
     * batches within the sky lane, then reconcile every section edge so all
     * affected chunks converge (MC-117067 / MC-117094). Ordinary player edits
     * stay on the incremental fast path.
     */
    private final World world;
    private final WorldHeightContext heightContext;
    private final ContextualLightManager contextualLight;

    private final SnapshotChunkMap loadedChunkMap = new SnapshotChunkMap();
    // Only accessed by server-thread packet/update callbacks, never by workers.
    private final DeferredChunkUpdates<Chunk> deferredChunkUpdates = new DeferredChunkUpdates<>();

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
    private final UnloadWaitBudget unloadWaitBudget = new UnloadWaitBudget(
            UnloadWaitBudget.DEFAULT_BUDGET_NS, System::nanoTime);

    public WorldLightManager(final World world, final boolean hasSkyLight, final boolean hasBlockLight) {
        this.world = world;
        this.heightContext = WorldUtil.getHeightContext(world);
        this.contextualLight = new ContextualLightManager(world, this.heightContext);
        this.skyQueue = hasSkyLight ? new LightQueue(this.heightContext) : null;
        // Block propagation consumes every position. Bulk sky work only peeks
        // at the set before rebuilding, so retain its cheaper hash-only enqueue path.
        this.blockQueue = hasBlockLight ? new LightQueue(this.heightContext, true) : null;
        this.stats = new LightStats(world.isRemote, world.provider.getDimension());
        if (this.skyQueue != null) this.skyQueue.setStats(this.stats);
        if (this.blockQueue != null) this.blockQueue.setStats(this.stats);
        this.initialLighting = new InitialLightCoordinator(
                this.loadedChunkMap, this.skyQueue, this.blockQueue);
        if (!world.isRemote) this.unloadWaitBudget.beginTick();
        this.skyWorker = hasSkyLight ? new LightEngineWorker(
                this.skyQueue,
                () -> new ScalarSkyEngine(world, this.heightContext),
                (task, engine) -> this.processWithGate(task, engine, true),
                this.stats.skyBudgetYields,
                "propagateSkyChanges",
                "Pulsar-Sky",
                !world.isRemote, world, this.stats.parallelJobsMax) : null;
        this.blockWorker = hasBlockLight ? new LightEngineWorker(
                this.blockQueue,
                () -> new ScalarBlockEngine(world, this.heightContext),
                (task, engine) -> this.processWithGate(task, engine, false),
                this.stats.blockBudgetYields,
                "propagateBlockChanges",
                "Pulsar-Block",
                !world.isRemote, world, this.stats.parallelJobsMax) : null;
        if (this.skyWorker != null) this.skyWorker.pairWith(this.blockWorker);
    }

    public void registerChunk(final Chunk chunk) {
        if (!this.world.isRemote) this.captureChunkLight(chunk);
        this.loadedChunkMap.put(CoordinateUtils.getChunkKey(chunk.x, chunk.z), chunk);
    }

    public void unregisterChunk(final int cx, final int cz) {
        this.clientRenderUpdates.remove(CoordinateUtils.getChunkKey(cx, cz));
        this.deferredChunkUpdates.remove(CoordinateUtils.getChunkKey(cx,cz));
        this.loadedChunkMap.remove(CoordinateUtils.getChunkKey(cx, cz));
        this.contextualLight.unload(cx, cz);
    }

    public ContextualLightManager contextualLight() {
        return this.contextualLight;
    }

    /** Forge WorldTick END includes tile ticks, unlike WorldServer.tick TAIL. */
    public void publishContextualLight() {
        if (!this.world.isRemote) {
            final boolean measure = PulsarConfig.debug.enableDebugStats;
            final long start = measure ? System.nanoTime() : 0L;
            this.contextualLight.flush(this);
            if (measure) this.stats.recordSampleFlush(System.nanoTime() - start);
        }
    }

    private void captureChunkLight(final Chunk chunk) {
        final boolean measure = PulsarConfig.debug.enableDebugStats;
        final long start = measure ? System.nanoTime() : 0L;
        this.contextualLight.load(chunk);
        if (measure) this.stats.recordSampleLoad(System.nanoTime() - start);
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

    /** Queue a recheck for the requested light type, if this world has that lane. */
    public void queueLightCheck(final EnumSkyBlock lightType, final int x, final int y, final int z) {
        if (!this.world.isRemote) this.contextualLight.request(x, y, z);
        final LightQueue queue = lightType == EnumSkyBlock.SKY ? this.skyQueue : this.blockQueue;
        if (queue != null) queue.queueBlockChange(x, y, z);
    }

    /** Queue a block change whose effects may involve both light types. */
    public void queueBlockChange(final int x, final int y, final int z) {
        if (!this.world.isRemote) this.contextualLight.request(x, y, z);
        this.queueSampledBlockChange(x, y, z);
    }

    void queueSampledBlockChange(final int x, final int y, final int z) {
        this.queueSampledBlockChange(x, y, z, ContextualLightSnapshot.BOTH_CHANGED);
    }

    void queueSampledBlockChange(final int x, final int y, final int z, final int changes) {
        if (this.skyQueue != null && (changes & ContextualLightSnapshot.SKY_CHANGED) != 0)
            this.skyQueue.queueBlockChange(x, y, z);
        if (this.blockQueue != null && (changes & ContextualLightSnapshot.BLOCK_CHANGED) != 0)
            this.blockQueue.queueBlockChange(x, y, z);
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
     * Thin-client tick: process both queues within a shared main-thread budget. The
     * engines write into SWMR arrays that share storage with the vanilla
     * nibbles and mark render updates directly, so there is no separate
     * publish/drain step.
     */
    public void processClientRenderUpdates() {
        final long started = System.nanoTime();
        final long budget = Math.max(1, Math.min(10, PulsarConfig.features.clientLightBudgetMs)) * 1_000_000L;
        final long deadline = started + budget;
        // Alternate the first lane so an expensive atomic task cannot always
        // consume the other lane's entire shared budget.
        final LightEngineWorker first = this.blockFirstClientTick ? this.blockWorker : this.skyWorker;
        final LightEngineWorker second = this.blockFirstClientTick ? this.skyWorker : this.blockWorker;
        this.blockFirstClientTick = !this.blockFirstClientTick;
        boolean processed;
        do {
            processed = first != null && first.processOnePendingUntil(deadline);
            // Both lanes get a turn; do not short-circuit after the first succeeds.
            processed |= second != null && second.processOnePendingUntil(deadline);
        } while (processed && System.nanoTime() - deadline < 0L);
        final int renderMarks = this.clientRenderUpdates.drain(this.loadedChunkMap::get, this::markClientRenderUpdate);
        if (LightStats.enabled) LightStats.engineRenderMarks += renderMarks;
        if (PulsarConfig.debug.enableDebugStats) {
            this.stats.recordClientDrain(System.nanoTime() - started, budget);
        }
        if (this.skyQueue != null) this.skyQueue.clearWorkSignal();
        if (this.blockQueue != null) this.blockQueue.clearWorkSignal();
        final int skySize = this.skyQueue != null ? this.skyQueue.size() : 0;
        final int blockSize = this.blockQueue != null ? this.blockQueue.size() : 0;
        this.stats.tick(skySize, blockSize);
    }

    /** Called exactly once at WorldTick END, never by a lighting worker. */
    public void tickServerStats() {
        final int skySize = this.skyQueue != null ? this.skyQueue.size() : 0;
        final int blockSize = this.blockQueue != null ? this.blockQueue.size() : 0;
        this.stats.tick(skySize, blockSize);
    }

    public void queueClientRenderUpdate(final Chunk chunk, final int sectionY, final long bounds) {
        if (!this.world.isRemote) throw new IllegalStateException("Client render notification on a server world");
        if (LightStats.enabled) LightStats.engineRenderRequests++;
        if (!PulsarConfig.features.coalesceClientRenderUpdates) {
            this.markClientRenderUpdate(CoordinateUtils.getChunkKey(chunk.x, chunk.z), sectionY, bounds);
            if (LightStats.enabled) LightStats.engineRenderMarks++;
            return;
        }
        this.clientRenderUpdates.add(CoordinateUtils.getChunkKey(chunk.x, chunk.z), chunk, sectionY, bounds);
    }

    private void markClientRenderUpdate(final long key, final int sectionY, final long bounds) {
        final int x = CoordinateUtils.getChunkX(key) << 4;
        final int y = sectionY << 4;
        final int z = CoordinateUtils.getChunkZ(key) << 4;
        this.world.markBlockRangeForRenderUpdate(x + RenderBounds.minX(bounds), y + RenderBounds.minY(bounds),
                z + RenderBounds.minZ(bounds), x + RenderBounds.maxX(bounds), y + RenderBounds.maxY(bounds),
                z + RenderBounds.maxZ(bounds));
    }

    /** Queues wake their worker on insertion; statistics advance only at tick end. */
    @Deprecated
    public void scheduleUpdate() {
        // Retained for callers compiled against earlier Pulsar versions.
    }

    /** Starts the shared unload-wait allowance for this world's next tick. */
    public void beginTickUnloadWaitBudget() {
        if (!this.world.isRemote) {
            this.unloadWaitBudget.beginTick();
        }
    }

    /** A block update packet has no scalar light; a section packet does, and must wait. */
    public boolean canSendUpdatedChunkLight(final int cx,final int cz) {
        final Chunk chunk=this.getLoadedChunk(cx,cz);
        if(chunk==null || !((PulsarChunk)chunk).pulsar$isLightReady()) return false;
        // Propagation from a neighboring task can also write this chunk's nibbles.
        for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++) {
            if(this.hasChunkPendingLight(cx+dx,cz+dz)) return false;
        }
        return true;
    }

    public void deferChunkPacketUpdate(final Chunk chunk) {
        this.deferredChunkUpdates.defer(CoordinateUtils.getChunkKey(chunk.x,chunk.z),chunk);
    }

    /** PlayerChunkMap clears its changed-entry set after update(), so it cannot own retries. */
    public void processDeferredChunkUpdates() {
        if(!(this.world instanceof WorldServer serverWorld)) return;
        this.deferredChunkUpdates.drain((key,chunk)-> {
            final PlayerChunkMapEntry entry=serverWorld.getPlayerChunkMap().getEntry(chunk.x,chunk.z);
            if(this.loadedChunkMap.get(key)!=chunk || entry==null || entry.getChunk()!=chunk)
                return DeferredChunkUpdates.Decision.DROP;
            return this.canSendUpdatedChunkLight(chunk.x,chunk.z)
                    ? DeferredChunkUpdates.Decision.SEND : DeferredChunkUpdates.Decision.WAIT;
        },(key,chunk)->serverWorld.getPlayerChunkMap().getEntry(chunk.x,chunk.z).update());
    }

    /** Refresh an existing client chunk without unloading its tracked entities. */
    public void sendChunkLightRefresh(final PlayerChunkMapEntry entry,final Chunk chunk,final int mask) {
        for(int part:ChunkUpdateMasks.split(mask,this.heightContext.getFullChunkSectionMask()))
            entry.sendPacket(new SPacketChunkData(chunk,part));
    }

    private void processWithGate(final ChunkTasks task, final PulsarEngine engine, final boolean sky) {
        this.lightingGate.readLock().lock();
        try {
            long generation = task.initialLightChunk != null ? task.initialLightGeneration
                    : task.initialLightEdgeGeneration;
            if (generation > 0L && !this.initialLighting.isCurrent(task.chunkCoordinate, generation)) return;
            if (sky) this.processSkyTask(task, engine);
            else this.processBlockTask(task, engine);
        } finally { this.lightingGate.readLock().unlock(); }
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

        final boolean promoteBulkChange = BulkSkyRelightPolicy.shouldPromoteColumns(
                task.initialLightChunk != null || task.initialLightEdgeGeneration > 0L,
                task.changedPositions);
        if (promoteBulkChange) {
            final Chunk chunk = this.loadedChunkMap.get(task.chunkCoordinate);
            if (chunk != null) {
                try {
                    // This recovery is deliberately sky-lane-only. Routing a
                    // dense skylight edit through the two-lane initial-light
                    // coordinator also rebuilt block light, which could erase
                    // the old source level before its queued removal had a
                    // chance to propagate into a neighbouring empty section.
                    int attempts = 0;
                    boolean overflowed;
                    do {
                        skyEngine.light(chunk, PulsarEngine.getEmptySectionsForChunk(chunk), false);
                        overflowed = skyEngine.wasQueueOverflowed();
                        attempts++;
                    } while (overflowed
                            && attempts <= InitialLightCoordinator.MAX_RELIGHT_ATTEMPTS);

                    if (overflowed) {
                        Pulsar.LOGGER.error(
                                "Sky engine: bulk relight for chunk ({}, {}) overflowed BFS queue {} times - giving up.",
                                cx, cz, attempts);
                    } else {
                        this.skyQueue.queueEdgeCheckAllSections(cx, cz, true);
                    }
                } catch (final Throwable t) {
                    if (this.loadedChunkMap.get(task.chunkCoordinate) != null) {
                        Pulsar.LOGGER.error("Bulk sky relight for chunk ({}, {}) failed", cx, cz, t);
                    }
                }
            }
            skyEngine.setStats(null);
            if (statsOn) {
                final long elapsed = System.nanoTime() - t0;
                this.stats.skyWorkerTimeNs.addAndGet(elapsed);
                this.stats.skyTaskMaxNs.accumulateAndGet(elapsed, Math::max);
                this.stats.skyTasksProcessed.incrementAndGet();
            }
            return;
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
                // A coordinated relight may absorb block/section changes
                // while it is still queued. Re-read emptiness at execution
                // time and let this single full pass cover that entire batch.
                skyEngine.light(task.initialLightChunk,
                        PulsarEngine.getEmptySectionsForChunk(task.initialLightChunk), false);
                valueOverflowed |= skyEngine.wasQueueOverflowed();
            } else if (task.changedSectionSet != null
                    || (task.changedPositions != null && !task.changedPositions.isEmpty())) {
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
            final long elapsed = System.nanoTime() - t0;
            this.stats.skyWorkerTimeNs.addAndGet(elapsed);
            this.stats.skyTaskMaxNs.accumulateAndGet(elapsed, Math::max);
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
                blockEngine.light(task.initialLightChunk,
                        PulsarEngine.getEmptySectionsForChunk(task.initialLightChunk), false);
                valueOverflowed |= blockEngine.wasQueueOverflowed();
            } else if (task.changedSectionSet != null
                    || (task.changedPositions != null && !task.changedPositions.isEmpty())) {
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
            this.stats.blockTaskMaxNs.accumulateAndGet(totalNs, Math::max);
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
        if (!this.world.isRemote) this.captureChunkLight(chunk);
        final Boolean[] emptySections = PulsarEngine.getEmptySectionsForChunk(chunk);
        final ChunkLightCompletion completion = this.initialLighting.queue(cx, cz, chunk, emptySections);
        this.refreshAfterRelight(key, completion);
        return true;
    }

    /** Loaded chunks only; insertion order should grow outward from the requested center. */
    public int forceRelightChunks(final Collection<Long> coordinates) {
        if (this.world.isRemote) throw new IllegalStateException("Range relight requires a server world");
        if (!this.contextualLight.isOwnerThread()) throw new IllegalStateException("Range relight requires the world thread");
        if (!PulsarConfig.features.experimentalRangeRelight) {
            int count = 0;
            for (final long key : new java.util.LinkedHashSet<>(coordinates))
                if (this.forceRelightChunk(CoordinateUtils.getChunkX(key), CoordinateUtils.getChunkZ(key))) count++;
            return count;
        }
        final Map<Long, Chunk> targets = new LinkedHashMap<>();
        final Map<Long, Chunk> available = new LinkedHashMap<>();
        final Map<Long, ChunkTasks> tasks = new LinkedHashMap<>();
        for (final long key : coordinates) {
            final Chunk chunk = this.loadedChunkMap.get(key);
            if (chunk == null || targets.containsKey(key)) continue;
            this.captureChunkLight(chunk);
            targets.put(key, chunk);
            final ChunkLightCompletion completion = this.initialLighting.queueDeferred(chunk,
                    PulsarEngine.getEmptySectionsForChunk(chunk));
            final ChunkTasks task = new ChunkTasks(key);
            task.initialLightChunk = chunk;
            task.initialLightGeneration = completion.generation;
            tasks.put(key, task);
            this.refreshAfterRelight(key, completion);
            for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) {
                final long nearKey = CoordinateUtils.getChunkKey(chunk.x + dx, chunk.z + dz);
                final Chunk near = this.loadedChunkMap.get(nearKey);
                if (near != null) available.put(nearKey, near);
            }
        }
        if (!targets.isEmpty()) RANGE_RELIGHTS.execute(() -> this.processRangeRelight(targets, available, tasks));
        return targets.size();
    }

    private void processRangeRelight(final Map<Long, Chunk> targets, final Map<Long, Chunk> available,
                                    final Map<Long, ChunkTasks> tasks) {
        this.lightingGate.writeLock().lock();
        try {
            java.util.function.LongPredicate current = key -> this.loadedChunkMap.get(key) == targets.get(key)
                    && this.initialLighting.isCurrent(key, tasks.get(key).initialLightGeneration);
            if (this.skyQueue != null) new ScalarSkyEngine(this.world, this.heightContext)
                    .relightChunks(targets, available, current);
            if (this.blockQueue != null) new ScalarBlockEngine(this.world, this.heightContext)
                    .relightChunks(targets, available, current);
            for (final var entry : tasks.entrySet()) {
                if (!current.test(entry.getKey())) continue;
                if (this.skyQueue != null) this.initialLighting.completeInitial(entry.getValue(), InitialLightCompletionState.SKY);
                if (this.blockQueue != null) this.initialLighting.completeInitial(entry.getValue(), InitialLightCompletionState.BLOCK);
            }
        } catch (final Throwable error) {
            Pulsar.LOGGER.error("Range relight failed; retrying current chunks through ordinary lighting", error);
            for (final var entry : tasks.entrySet()) {
                final ChunkTasks task = entry.getValue();
                if (this.loadedChunkMap.get(entry.getKey()) != task.initialLightChunk
                        || !this.initialLighting.isCurrent(entry.getKey(), task.initialLightGeneration)) continue;
                final Boolean[] empty = PulsarEngine.getEmptySectionsForChunk(task.initialLightChunk);
                if (this.skyQueue != null) this.skyQueue.queueChunkLight(task.initialLightChunk.x,
                        task.initialLightChunk.z, task.initialLightChunk, empty, task.initialLightGeneration);
                if (this.blockQueue != null) this.blockQueue.queueChunkLight(task.initialLightChunk.x,
                        task.initialLightChunk.z, task.initialLightChunk, empty, task.initialLightGeneration);
            }
        } finally { this.lightingGate.writeLock().unlock(); }
    }

    private void refreshAfterRelight(final long key, final ChunkLightCompletion completion) {
        final int cx = CoordinateUtils.getChunkX(key), cz = CoordinateUtils.getChunkZ(key);

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
                        this.sendChunkLightRefresh(entry,current,this.heightContext.getFullChunkSectionMask());
                    }
                });
            }, Runnable::run);
        }
    }

    /**
     * True while queued work could still change this chunk's light values
     * (pending initial light, block/section changes, or an unfinished
     * propagation/edge completion latch). Ordinary edge-check-only tasks do
     * not count. Used to decide whether the current SWMR data is safe to
     * persist as valid.
     */
    public boolean hasPendingLightWork(final int cx, final int cz) {
        if (this.contextualLight.hasPending(cx, cz)) return true;
        final long key = CoordinateUtils.getChunkKey(cx, cz);
        if (this.initialLighting.hasPending(key)) {
            return true;
        }
        return (this.skyQueue != null && this.skyQueue.hasPendingLightWork(key))
                || (this.blockQueue != null && this.blockQueue.hasPendingLightWork(key));
    }

    /** Any 3x3 light or contextual task can still publish data into this chunk. */
    public boolean hasPendingLightWorkNear(final int cx, final int cz) {
        for (int dz = -1; dz <= 1; ++dz) {
            for (int dx = -1; dx <= 1; ++dx) {
                final long key = CoordinateUtils.getChunkKey(cx + dx, cz + dz);
                if (this.contextualLight.hasPending(cx + dx, cz + dz)
                        || this.getPendingWorkFutureAt(key) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Wait briefly for all queued or in-flight work touching a chunk. Returns
     * {@code false} on timeout/interruption so unload can invalidate the saved
     * light instead of serialising data while a worker may still mutate it.
     */
    public boolean awaitPendingWork(final int cx, final int cz) {
        if (this.world.isRemote) {
            return true;
        }

        long waitedNs = 0L;
        while (true) {
            final Future<Void> pending = this.getPendingWorkFutureNear(cx, cz);
            if (pending == null) {
                return this.finishUnloadWait(waitedNs, true);
            }
            final long remaining = this.unloadWaitBudget.remainingNs();
            if (remaining <= 0L) {
                this.stats.recordUnloadWaitBudgetExhausted();
                return this.finishUnloadWait(waitedNs, false);
            }
            final long waitStartedAtNs = System.nanoTime();
            try {
                pending.get(remaining, TimeUnit.NANOSECONDS);
            } catch (final InterruptedException e) {
                waitedNs += this.recordUnloadWaitSince(waitStartedAtNs);
                Thread.currentThread().interrupt();
                Pulsar.LOGGER.warn("Interrupted while waiting for light work on chunk ({}, {})", cx, cz);
                return this.finishUnloadWait(waitedNs, false);
            } catch (final TimeoutException e) {
                waitedNs += this.recordUnloadWaitSince(waitStartedAtNs);
                this.stats.recordUnloadWaitTimeout();
                if (this.unloadWaitBudget.remainingNs() == 0L) {
                    this.stats.recordUnloadWaitBudgetExhausted();
                }
                return this.finishUnloadWait(waitedNs, false);
            } catch (final CancellationException | java.util.concurrent.ExecutionException e) {
                waitedNs += this.recordUnloadWaitSince(waitStartedAtNs);
                return this.finishUnloadWait(waitedNs, false);
            }
            waitedNs += this.recordUnloadWaitSince(waitStartedAtNs);
        }
    }

    private long recordUnloadWaitSince(final long startedAtNs) {
        return this.unloadWaitBudget.recordWaitSince(startedAtNs);
    }

    private boolean finishUnloadWait(final long waitedNs, final boolean settled) {
        this.stats.recordUnloadWait(waitedNs);
        return settled;
    }

    public void recordUnloadLightInvalidation() {
        this.stats.recordUnloadLightInvalidation();
    }

    private Future<Void> getPendingWorkFutureAt(final long key) {
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

    private Future<Void> getPendingWorkFutureNear(final int cx, final int cz) {
        return ChunkWorkNeighborhood.findPendingFuture(cx, cz, this::getPendingWorkFutureAt);
    }

    public void shutdown() {
        // Late jobs must fail the same registration checks as chunk unload.
        this.loadedChunkMap.clear();
        this.clientRenderUpdates.clear();
        this.deferredChunkUpdates.clear();
        if (this.skyWorker != null) this.skyWorker.requestStop();
        if (this.blockWorker != null) this.blockWorker.requestStop();
        if (this.skyWorker != null) this.skyWorker.awaitStop();
        if (this.blockWorker != null) this.blockWorker.awaitStop();
        this.stats.close();
    }

}
