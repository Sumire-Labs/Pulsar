package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.util.CoordinateUtils;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import net.minecraft.world.chunk.Chunk;

import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * Synchronized insertion-ordered queue of per-chunk light tasks. Main thread
 * enqueues; light worker thread dequeues via {@link WorldLightManager}.
 */
public final class LightQueue {

    private final WorldHeightContext heightContext;
    private final Long2ObjectLinkedOpenHashMap<ChunkTasks> tasksByChunk = new Long2ObjectLinkedOpenHashMap<>();
    // Each queue has exactly one consumer. A task is moved here while still
    // holding this queue's monitor, closing the observability gap between
    // dequeue and worker completion.
    private final Long2ObjectOpenHashMap<ChunkTasks> inFlightTasks = new Long2ObjectOpenHashMap<>();
    private final Semaphore workAvailable = new Semaphore(0);
    private LightStats stats;

    // Priority lookup support. The drain loop asks for "first task with block
    // changes" / "first task with initial light" once per task processed, so
    // these must be O(1): keys are enqueued on the transition into each class
    // and validated lazily on pop (an entry may be stale if its task was
    // removed through another path). The counter is exact: incremented on the
    // null->non-null transition, decremented whenever a task holding an
    // initial light leaves the map.
    private final LongArrayFIFOQueue blockChangeKeys = new LongArrayFIFOQueue();
    private final LongArrayFIFOQueue initialLightKeys = new LongArrayFIFOQueue();
    private int initialLightCount;

    public LightQueue(final WorldHeightContext heightContext) {
        this.heightContext = heightContext;
    }

    void setStats(final LightStats stats) {
        this.stats = stats;
    }

    private ChunkTasks getOrCreate(final long key) {
        ChunkTasks tasks = this.tasksByChunk.get(key);
        if (tasks == null) {
            tasks = new ChunkTasks(key);
            this.tasksByChunk.put(key, tasks);
            if (this.stats != null && LightStats.enabled) {
                this.stats.chunksQueued.incrementAndGet();
            }
        }
        return tasks;
    }

    public synchronized void queueBlockChange(final int x, final int y, final int z) {
        final long key = CoordinateUtils.getChunkKey(x >> 4, z >> 4);
        final ChunkTasks tasks = this.getOrCreate(key);
        if (tasks.changedPositions == null) {
            tasks.changedPositions = new IntOpenHashSet();
            this.blockChangeKeys.enqueue(key);
        }
        tasks.changedPositions.add((x & 15) | ((z & 15) << 4) | (y << 8));
        this.workAvailable.release(1);
    }

    public synchronized void queueSectionChange(final int cx, final int sectionY, final int cz, final boolean empty) {
        final int sectionIndex = this.heightContext.getSectionIndex(sectionY);
        if (sectionIndex < 0) {
            return;
        }
        final long key = CoordinateUtils.getChunkKey(cx, cz);
        final ChunkTasks tasks = this.getOrCreate(key);
        if (tasks.changedSectionSet == null) {
            tasks.changedSectionSet = new Boolean[this.heightContext.getTotalSections()];
        }
        tasks.changedSectionSet[sectionIndex] = empty;
        this.workAvailable.release(1);
    }

    public synchronized void queueChunkLight(final int cx, final int cz, final Chunk chunk,
                                             final Boolean[] emptySections, final long generation) {
        final long key = CoordinateUtils.getChunkKey(cx, cz);
        final ChunkTasks tasks = this.getOrCreate(key);
        if ((tasks.initialLightChunk != null && tasks.initialLightGeneration > generation)
                || tasks.initialLightEdgeGeneration > generation) {
            return;
        }
        if (tasks.initialLightChunk == null) {
            this.initialLightKeys.enqueue(key);
            this.initialLightCount++;
        }
        tasks.initialLightChunk = chunk;
        tasks.initialLightEmptySections = emptySections;
        tasks.initialLightGeneration = generation;
        tasks.relightAttempts = 0;
        // A new propagation pass supersedes any queued edge-finalisation
        // marker. Its accumulated section set may remain as ordinary edge
        // maintenance and will be checked again after this generation.
        tasks.initialLightEdgeGeneration = 0L;
        tasks.edgeCheckAttempts = 0;
        this.workAvailable.release(1);
    }

    /**
     * Queue the cheap load-time init (nibble/emptiness-map setup, no BFS) for
     * a chunk restored with valid persisted light.
     */
    public synchronized void queueChunkLoadInit(final int cx, final int cz, final Chunk chunk, final Boolean[] emptySections) {
        final long key = CoordinateUtils.getChunkKey(cx, cz);
        final ChunkTasks tasks = this.getOrCreate(key);
        tasks.loadInitChunk = chunk;
        tasks.loadInitEmptySections = emptySections;
        this.workAvailable.release(1);
    }

    /**
     * Re-queue a chunk for full relighting after a queue overflow. Increments
     * the relight attempt counter.
     *
     * @return {@code false} when a newer full-relight generation already
     * occupies this chunk's queued batch
     */
    public synchronized boolean requeueChunkLight(final int cx, final int cz, final Chunk chunk,
                                                  final Boolean[] emptySections, final long generation,
                                                  final int previousAttempts) {
        final long key = CoordinateUtils.getChunkKey(cx, cz);
        final ChunkTasks tasks = this.getOrCreate(key);
        if ((tasks.initialLightChunk != null && tasks.initialLightGeneration > generation)
                || tasks.initialLightEdgeGeneration > generation) {
            return false;
        }
        if (tasks.initialLightChunk == null) {
            this.initialLightKeys.enqueue(key);
            this.initialLightCount++;
        }
        tasks.initialLightChunk = chunk;
        tasks.initialLightEmptySections = emptySections;
        if (tasks.initialLightGeneration == generation) {
            tasks.relightAttempts = Math.max(tasks.relightAttempts, previousAttempts + 1);
        } else {
            tasks.initialLightGeneration = generation;
            tasks.relightAttempts = previousAttempts + 1;
        }
        tasks.initialLightEdgeGeneration = 0L;
        tasks.edgeCheckAttempts = 0;
        this.workAvailable.release(1);
        return true;
    }

    public synchronized void queueEdgeCheck(final int cx, final int cz, final int sectionY, final boolean isSky) {
        final long key = CoordinateUtils.getChunkKey(cx, cz);
        final ChunkTasks tasks = this.getOrCreate(key);
        if (isSky) {
            if (tasks.queuedEdgeChecksSky == null) {
                tasks.queuedEdgeChecksSky = new IntOpenHashSet();
            }
            tasks.queuedEdgeChecksSky.add(sectionY);
        } else {
            if (tasks.queuedEdgeChecksBlock == null) {
                tasks.queuedEdgeChecksBlock = new IntOpenHashSet();
            }
            tasks.queuedEdgeChecksBlock.add(sectionY);
        }
        this.workAvailable.release(1);
    }

    /**
     * Queue edge checks for all light sections on a chunk.
     */
    public synchronized void queueEdgeCheckAllSections(final int cx, final int cz, final boolean isSky) {
        final long key = CoordinateUtils.getChunkKey(cx, cz);
        final ChunkTasks tasks = this.getOrCreate(key);
        this.addAllEdgeSections(tasks, isSky);
        this.workAvailable.release(1);
    }

    /**
     * Queue the edge-reconciliation phase that gates publication of an
     * initial-light generation.
     *
     * @return {@code false} if queued work from a newer generation already
     * occupies this chunk
     */
    public synchronized boolean queueInitialLightEdgeCheckAllSections(final int cx, final int cz,
                                                                       final boolean isSky,
                                                                       final long generation,
                                                                       final int attempts) {
        if (generation <= 0L || attempts < 0) {
            throw new IllegalArgumentException("Invalid initial-light edge generation/attempt");
        }
        final long key = CoordinateUtils.getChunkKey(cx, cz);
        final ChunkTasks tasks = this.getOrCreate(key);
        if ((tasks.initialLightChunk != null && tasks.initialLightGeneration >= generation)
                || tasks.initialLightEdgeGeneration > generation) {
            return false;
        }
        this.addAllEdgeSections(tasks, isSky);
        if (tasks.initialLightEdgeGeneration == generation) {
            tasks.edgeCheckAttempts = Math.max(tasks.edgeCheckAttempts, attempts);
        } else {
            tasks.initialLightEdgeGeneration = generation;
            tasks.edgeCheckAttempts = attempts;
        }
        this.workAvailable.release(1);
        return true;
    }

    private void addAllEdgeSections(final ChunkTasks tasks, final boolean isSky) {
        if (isSky) {
            if (tasks.queuedEdgeChecksSky == null) {
                tasks.queuedEdgeChecksSky = new IntOpenHashSet();
            }
            for (int s = this.heightContext.getMinLightSection(); s <= this.heightContext.getMaxLightSection(); ++s) {
                tasks.queuedEdgeChecksSky.add(s);
            }
        } else {
            if (tasks.queuedEdgeChecksBlock == null) {
                tasks.queuedEdgeChecksBlock = new IntOpenHashSet();
            }
            for (int s = this.heightContext.getMinLightSection(); s <= this.heightContext.getMaxLightSection(); ++s) {
                tasks.queuedEdgeChecksBlock.add(s);
            }
        }
    }

    /**
     * Remove and return the first task that has initial lighting work. Used to
     * prioritize initial lights over edge-check-only tasks.
     */
    public synchronized ChunkTasks removeFirstInitialLightTask() {
        while (!this.initialLightKeys.isEmpty()) {
            final long key = this.initialLightKeys.dequeueLong();
            final ChunkTasks task = this.tasksByChunk.get(key);
            if (task == null || task.initialLightChunk == null) {
                continue; // stale entry: task left the map through another path
            }
            this.tasksByChunk.remove(key);
            this.onTaskDequeued(task);
            return task;
        }
        return null;
    }

    public synchronized ChunkTasks removeFirstTask() {
        if (this.tasksByChunk.isEmpty()) {
            return null;
        }
        final long key = this.tasksByChunk.firstLongKey();
        final ChunkTasks task = this.tasksByChunk.remove(key);
        this.onTaskDequeued(task);
        return task;
    }

    /**
     * Remove and return the first task that has block changes
     * (changedPositions non-empty), skipping initial-lighting-only tasks.
     * Returns null if no such task exists. Used to prioritize player block
     * placement/breaking over chunk loading.
     */
    public synchronized ChunkTasks removeFirstBlockChangeTask() {
        while (!this.blockChangeKeys.isEmpty()) {
            final long key = this.blockChangeKeys.dequeueLong();
            final ChunkTasks task = this.tasksByChunk.get(key);
            if (task == null || task.changedPositions == null || task.changedPositions.isEmpty()) {
                continue; // stale entry: task left the map through another path
            }
            this.tasksByChunk.remove(key);
            this.onTaskDequeued(task);
            return task;
        }
        return null;
    }

    /**
     * Check whether any queued task has initial lighting work. Used for
     * priority preemption — lets initial light tasks interrupt long-running
     * edge check phases.
     */
    public synchronized boolean hasInitialLightTask() {
        return this.initialLightCount > 0;
    }

    public void removeChunk(final int cx, final int cz) {
        final ChunkTasks task;
        synchronized (this) {
            task = this.tasksByChunk.remove(CoordinateUtils.getChunkKey(cx, cz));
            if (task != null) {
                this.onTaskRemoved(task);
            }
        }
        if (task != null) {
            task.onComplete.set(null);
        }
    }

    /**
     * Finish a task previously returned by one of the dequeue methods.
     * Always called from a worker {@code finally} block.
     */
    void completeTask(final ChunkTasks task) {
        synchronized (this) {
            if (this.inFlightTasks.get(task.chunkCoordinate) == task) {
                this.inFlightTasks.remove(task.chunkCoordinate);
            }
        }
        task.onComplete.set(null);
    }

    /**
     * Bookkeeping for every path that removes a task from the map.
     */
    private void onTaskRemoved(final ChunkTasks task) {
        if (task.initialLightChunk != null) {
            this.initialLightCount--;
        }
    }

    /**
     * Atomically move a dequeued task into the in-flight set.
     */
    private void onTaskDequeued(final ChunkTasks task) {
        this.onTaskRemoved(task);
        this.inFlightTasks.put(task.chunkCoordinate, task);
    }

    public synchronized boolean hasPendingWork(final int cx, final int cz) {
        final long key = CoordinateUtils.getChunkKey(cx, cz);
        return this.tasksByChunk.containsKey(key) || this.inFlightTasks.containsKey(key);
    }

    public synchronized boolean isEmpty() {
        return this.tasksByChunk.isEmpty();
    }

    public synchronized int size() {
        return this.tasksByChunk.size();
    }

    /**
     * True when this queue still holds work that can change the chunk's light
     * VALUES: initial light, block changes or section changes. Edge-check-only
     * tasks are excluded — they refine seams but a save taken before them is
     * not wrong enough to warrant a full relight.
     */
    public synchronized boolean hasPendingLightWork(final long key) {
        return changesLightValues(this.tasksByChunk.get(key))
                || changesLightValues(this.inFlightTasks.get(key));
    }

    /**
     * Completion for the current queued or in-flight batch, or {@code null}
     * when this queue has no work for the chunk. Callers re-check after the
     * returned future completes because a new batch may have arrived while
     * the previous one was running.
     */
    synchronized Future<Void> getPendingWorkFuture(final long key) {
        final ChunkTasks inFlight = this.inFlightTasks.get(key);
        if (inFlight != null) {
            return inFlight.onComplete;
        }
        final ChunkTasks queued = this.tasksByChunk.get(key);
        return queued == null ? null : queued.onComplete;
    }

    private static boolean changesLightValues(final ChunkTasks tasks) {
        if (tasks == null) {
            return false;
        }
        return tasks.initialLightChunk != null
                || tasks.changedSectionSet != null
                || (tasks.changedPositions != null && !tasks.changedPositions.isEmpty());
    }

    /**
     * Block until work is available. Drains all excess permits so we process
     * everything per wake.
     */
    void waitForWork() throws InterruptedException {
        this.workAvailable.acquire();
        this.workAvailable.drainPermits();
    }

    /**
     * Wake the worker thread (e.g., for shutdown).
     */
    void wakeUp() {
        this.workAvailable.release(1);
    }

    /**
     * Thin-client mode: no worker ever acquires the permits, so drop them
     * each drain to keep the semaphore from accumulating without bound.
     */
    void clearWorkSignal() {
        this.workAvailable.drainPermits();
    }
}
