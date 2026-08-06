package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.Pulsar;
import com.sumirelabs.pulsar.light.engine.PulsarEngine;
import com.sumirelabs.pulsar.util.CoordinateUtils;
import com.sumirelabs.pulsar.util.SnapshotChunkMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.chunk.Chunk;

import java.util.concurrent.Future;

/**
 * Coordinates the two phases of initial chunk lighting.
 *
 * <p>Sky and block propagation may finish on different workers. A chunk is
 * made usable after every active lane finishes propagation, and is published
 * as light-ready only after every lane also finishes edge reconciliation for
 * the same generation. This class owns the generation lock and keeps those
 * transitions out of {@link WorldLightManager}'s queue orchestration code.
 */
final class InitialLightCoordinator {

    static final int MAX_RELIGHT_ATTEMPTS = 2;

    private final SnapshotChunkMap loadedChunks;
    private final LightQueue skyQueue;
    private final LightQueue blockQueue;
    private final Runnable updateScheduler;

    private final Object lock = new Object();
    private final Long2ObjectOpenHashMap<ChunkLightCompletion> completions =
            new Long2ObjectOpenHashMap<>();
    private long nextGeneration;

    InitialLightCoordinator(final SnapshotChunkMap loadedChunks,
                            final LightQueue skyQueue,
                            final LightQueue blockQueue,
                            final Runnable updateScheduler) {
        this.loadedChunks = loadedChunks;
        this.skyQueue = skyQueue;
        this.blockQueue = blockQueue;
        this.updateScheduler = updateScheduler;
    }

    ChunkLightCompletion queue(final int chunkX, final int chunkZ, final Chunk chunk,
                               final Boolean[] emptySections) {
        return this.queue(chunkX, chunkZ, chunk, emptySections, 0, false);
    }

    /**
     * Starts a replacement generation after overflow and chains the old
     * completion to the replacement so existing waiters observe its result.
     */
    ChunkLightCompletion queueRecovery(final int chunkX, final int chunkZ, final Chunk chunk,
                                       final Boolean[] emptySections,
                                       final int edgeRecoveryAttempts) {
        return this.queue(chunkX, chunkZ, chunk, emptySections, edgeRecoveryAttempts, true);
    }

    private ChunkLightCompletion queue(final int chunkX, final int chunkZ, final Chunk chunk,
                                       final Boolean[] emptySections,
                                       final int edgeRecoveryAttempts,
                                       final boolean handoffSupersededCompletion) {
        if (edgeRecoveryAttempts < 0 || edgeRecoveryAttempts > MAX_RELIGHT_ATTEMPTS) {
            throw new IllegalArgumentException("Invalid edge-recovery attempt: " + edgeRecoveryAttempts);
        }

        final long key = CoordinateUtils.getChunkKey(chunkX, chunkZ);
        final ChunkLightCompletion completion;
        final ChunkLightCompletion superseded;

        synchronized (this.lock) {
            if (this.nextGeneration == Long.MAX_VALUE) {
                throw new IllegalStateException("Initial-light generation counter exhausted");
            }
            final long generation = ++this.nextGeneration;
            final int requiredLanes = (this.skyQueue != null ? InitialLightCompletionState.SKY : 0)
                    | (this.blockQueue != null ? InitialLightCompletionState.BLOCK : 0);
            completion = new ChunkLightCompletion(generation, requiredLanes, chunk, edgeRecoveryAttempts);
            superseded = this.completions.put(key, completion);

            // Queue both lanes while holding the generation lock so a newer
            // request cannot be followed by an older task insertion.
            final PulsarChunk pulsarChunk = (PulsarChunk) chunk;
            pulsarChunk.pulsar$setLightReady(false);
            pulsarChunk.pulsar$setLightUsable(false);
            if (this.skyQueue != null) {
                this.skyQueue.queueChunkLight(chunkX, chunkZ, chunk, emptySections, generation);
            }
            if (this.blockQueue != null) {
                this.blockQueue.queueChunkLight(chunkX, chunkZ, chunk, emptySections, generation);
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

    void removeChunk(final int chunkX, final int chunkZ) {
        final long key = CoordinateUtils.getChunkKey(chunkX, chunkZ);
        final ChunkLightCompletion removed;
        synchronized (this.lock) {
            removed = this.completions.remove(key);
            if (this.skyQueue != null) {
                this.skyQueue.removeChunk(chunkX, chunkZ);
            }
            if (this.blockQueue != null) {
                this.blockQueue.removeChunk(chunkX, chunkZ);
            }
        }
        if (removed != null) {
            removed.finish(false);
        }
    }

    /**
     * Restarts coordinated lighting after final edge reconciliation overflows.
     * A fresh generation is required because an edge-only retry cannot recover
     * updates dropped after propagation moved away from the seam.
     */
    boolean restartAfterEdgeOverflow(final ChunkTasks task, final int chunkX, final int chunkZ,
                                     final String engineName) {
        if (task.initialLightEdgeGeneration <= 0L) {
            return false;
        }
        if (task.edgeCheckAttempts >= MAX_RELIGHT_ATTEMPTS) {
            Pulsar.LOGGER.error(
                    "{} engine: chunk ({}, {}) overflowed final edge reconciliation {} times - giving up.",
                    engineName, chunkX, chunkZ, task.edgeCheckAttempts + 1);
            return false;
        }

        synchronized (this.lock) {
            final ChunkLightCompletion completion = this.completions.get(task.chunkCoordinate);
            if (completion == null || completion.generation != task.initialLightEdgeGeneration) {
                // A newer generation already superseded this edge pass.
                return true;
            }
            if (this.loadedChunks.get(task.chunkCoordinate) != completion.chunk) {
                return false;
            }
            final Boolean[] emptySections = PulsarEngine.getEmptySectionsForChunk(completion.chunk);
            this.queueRecovery(
                    chunkX, chunkZ, completion.chunk, emptySections, task.edgeCheckAttempts + 1);
            this.updateScheduler.run();
            return true;
        }
    }

    /**
     * Records one lane's initial propagation. Once every required lane is
     * complete, the chunk becomes usable and generation-tagged edge passes are
     * queued. Public light-ready state remains false during this phase.
     */
    void completeInitial(final ChunkTasks task, final int lane) {
        if (task.initialLightGeneration <= 0L) {
            return;
        }

        final long chunkCoordinate = task.chunkCoordinate;
        ChunkLightCompletion failedCompletion = null;
        Throwable transitionFailure = null;

        synchronized (this.lock) {
            final ChunkLightCompletion completion = this.completions.get(chunkCoordinate);
            if (completion == null) {
                return;
            }
            final InitialLightCompletionState.Result result =
                    completion.state.completeInitial(task.initialLightGeneration, lane);
            if (result != InitialLightCompletionState.Result.INITIAL_COMPLETE) {
                return;
            }

            final PulsarChunk pulsarChunk = (PulsarChunk) completion.chunk;
            if (this.loadedChunks.get(chunkCoordinate) != completion.chunk) {
                this.completions.remove(chunkCoordinate);
                pulsarChunk.pulsar$setLightReady(false);
                pulsarChunk.pulsar$setLightUsable(false);
                failedCompletion = completion;
            } else {
                try {
                    pulsarChunk.pulsar$setLightUsable(true);
                    final int chunkX = CoordinateUtils.getChunkX(chunkCoordinate);
                    final int chunkZ = CoordinateUtils.getChunkZ(chunkCoordinate);
                    final boolean skyQueued = this.skyQueue == null
                            || this.skyQueue.queueInitialLightEdgeCheckAllSections(
                            chunkX, chunkZ, true, completion.generation, completion.edgeRecoveryAttempts);
                    final boolean blockQueued = this.blockQueue == null
                            || this.blockQueue.queueInitialLightEdgeCheckAllSections(
                            chunkX, chunkZ, false, completion.generation, completion.edgeRecoveryAttempts);
                    if (!skyQueued || !blockQueued) {
                        throw new IllegalStateException("A newer light task occupied the edge queue");
                    }
                } catch (final Throwable t) {
                    this.completions.remove(chunkCoordinate);
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
     * Records one generation-tagged edge pass. The final required lane copies
     * reconciled light into vanilla storage and publishes the chunk.
     */
    void completeEdges(final ChunkTasks task, final int lane) {
        if (task.initialLightEdgeGeneration <= 0L) {
            return;
        }

        final long chunkCoordinate = task.chunkCoordinate;
        final ChunkLightCompletion completion;

        synchronized (this.lock) {
            completion = this.completions.get(chunkCoordinate);
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
        // Serialize publication only against another generation of this chunk.
        // Generation checks around the copy prevent stale publication.
        synchronized (completion.chunk) {
            boolean currentGeneration;
            synchronized (this.lock) {
                currentGeneration = this.completions.get(chunkCoordinate) == completion
                        && this.loadedChunks.get(chunkCoordinate) == completion.chunk;
            }

            try {
                if (currentGeneration) {
                    ((PulsarChunk) completion.chunk).pulsar$syncLightToVanilla();
                }
            } catch (final Throwable t) {
                publishFailure = t;
            }

            synchronized (this.lock) {
                if (this.completions.get(chunkCoordinate) == completion) {
                    this.completions.remove(chunkCoordinate);
                    currentGeneration = this.loadedChunks.get(chunkCoordinate) == completion.chunk;
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

    boolean hasPending(final long chunkCoordinate) {
        synchronized (this.lock) {
            return this.completions.containsKey(chunkCoordinate);
        }
    }

    Future<Void> getPendingFuture(final long chunkCoordinate) {
        synchronized (this.lock) {
            final ChunkLightCompletion completion = this.completions.get(chunkCoordinate);
            return completion == null ? null : completion.future;
        }
    }
}
