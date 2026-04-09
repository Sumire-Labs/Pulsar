package com.sumirelabs.pulsar.util;

import net.minecraft.world.chunk.Chunk;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe map of {@link Chunk} keyed by packed chunk coordinates.
 *
 * <p>Originally ported from SuperNova 1.7.10, which used a
 * {@code Long2ObjectOpenHashMap} and published a fresh {@code clone()} after
 * every {@code put}/{@code remove}. That strategy degrades to O(N²) during
 * bursts of chunk loads — long-distance teleports in 1.12.2 with view
 * distance 32 load ~1000 chunks back-to-back and each one cloned the
 * growing map, which measurably delayed the server tick.
 *
 * <p>Pulsar's refinement: back the map with {@link ConcurrentHashMap}.
 * Writers pay one {@link Long} boxing per operation (cheap), readers are
 * lock-free on all JVM-supported platforms, and there is no clone cost at
 * all. The thread-local fastutil map was nice to have but not worth the
 * O(N²) worst case.
 *
 * <p>{@link ConcurrentHashMap} provides happens-before semantics between
 * {@code put} and subsequent {@code get}s across threads, which is what
 * Pulsar's sky / block BFS workers need when they peek at loaded chunks.
 */
public final class SnapshotChunkMap {

    private final ConcurrentHashMap<Long, Chunk> map = new ConcurrentHashMap<>(256);

    public void put(final long key, final Chunk value) {
        map.put(key, value);
    }

    public Chunk remove(final long key) {
        return map.remove(key);
    }

    public Chunk get(final long key) {
        return map.get(key);
    }
}
