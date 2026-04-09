package com.sumirelabs.pulsar.light;

import com.google.common.util.concurrent.SettableFuture;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.world.chunk.Chunk;

/**
 * Per-chunk batched light work. Accumulates block changes, section changes,
 * initial lighting requests, and edge checks until processed by
 * {@link WorldLightManager}.
 */
public final class ChunkTasks {

    public final long chunkCoordinate;
    public IntOpenHashSet changedPositions;

    /**
     * Per-section emptiness changes. Tri-state:
     * {@code null} = no change, {@code TRUE} = empty, {@code FALSE} = non-empty.
     */
    public Boolean[] changedSectionSet;

    /** Non-null if this chunk needs initial lighting. */
    public Chunk initialLightChunk;
    public Boolean[] initialLightEmptySections;

    public IntOpenHashSet queuedEdgeChecksSky;
    public IntOpenHashSet queuedEdgeChecksBlock;

    public final SettableFuture<Void> onComplete;

    public final long enqueueTimeNs;
    public int relightAttempts;

    public ChunkTasks(final long chunkCoordinate) {
        this.chunkCoordinate = chunkCoordinate;
        this.onComplete = SettableFuture.create();
        this.enqueueTimeNs = System.nanoTime();
    }
}
