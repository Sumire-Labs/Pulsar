package com.sumirelabs.pulsar.util;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.chunk.Chunk;

/**
 * Single-writer, multi-reader map of {@link Chunk} keyed by packed chunk
 * coordinates.
 *
 * <p>Ported from SuperNova 1.7.10. The owner thread mutates the live map and
 * publishes a clone via the {@code volatile snapshot} field after every
 * mutation. Reader threads observe the most recently published snapshot
 * without locking.
 *
 * <p>Used by Pulsar's worker threads to safely query loaded chunks without
 * blocking the main thread or risking concurrent-modification crashes.
 */
public final class SnapshotChunkMap {

    private final Long2ObjectOpenHashMap<Chunk> map = new Long2ObjectOpenHashMap<>();
    private volatile Long2ObjectOpenHashMap<Chunk> snapshot = new Long2ObjectOpenHashMap<>();
    private final Thread ownerThread = Thread.currentThread();

    public void put(final long key, final Chunk value) {
        map.put(key, value);
        snapshot = map.clone();
    }

    public Chunk remove(final long key) {
        final Chunk removed = map.remove(key);
        snapshot = map.clone();
        return removed;
    }

    public Chunk get(final long key) {
        if (Thread.currentThread() == ownerThread) {
            return map.get(key);
        }
        return snapshot.get(key);
    }
}
