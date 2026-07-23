package com.sumirelabs.pulsar.light;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

/**
 * Deduplicated queue of per-section render updates with tight changed-block
 * bounds. Worker threads (sky + block) offer {@code (sectionKey, bounds)}
 * pairs; repeated offers for the same section union their bounds, so a
 * section is marked for render at most once per drain regardless of how many
 * BFS passes touched it. The main thread drains once per client tick.
 *
 * <p>{@code sectionKey} packs {@code (cx << 32) | ((cz & 0xFFFF) << 16) | (cy & 0xFFFF)}.
 *
 * <p>{@code bounds} packs six section-local nibbles:
 * {@code minX | minY << 4 | minZ << 8 | maxX << 12 | maxY << 16 | maxZ << 20}.
 * Marking only the actually-changed range matters because vanilla's
 * {@code markBlockRangeForRenderUpdate} already inflates the range by one
 * block in each direction — a full-section mark therefore rebuilds all 26
 * neighbouring RenderChunks, while a tight interior range rebuilds one.
 *
 * <p>Double-buffered: drain swaps in a pre-allocated map so producers aren't
 * blocked during iteration.
 */
public final class RenderUpdateQueue {

    /** Bounds covering the whole section (0,0,0)-(15,15,15). */
    public static final long FULL_SECTION_BOUNDS = packBounds(0, 0, 0, 15, 15, 15);

    @FunctionalInterface
    public interface SectionBoundsConsumer {

        void accept(long sectionKey, long bounds);
    }

    private Long2LongOpenHashMap writeMap;
    private Long2LongOpenHashMap drainMap;

    public RenderUpdateQueue() {
        this(4096);
    }

    public RenderUpdateQueue(final int initialCapacity) {
        this.writeMap = new Long2LongOpenHashMap(initialCapacity);
        this.drainMap = new Long2LongOpenHashMap(initialCapacity);
    }

    public static long packBounds(final int minX, final int minY, final int minZ,
                                  final int maxX, final int maxY, final int maxZ) {
        return (long) (minX | (minY << 4) | (minZ << 8) | (maxX << 12) | (maxY << 16) | (maxZ << 20));
    }

    public static long unionBounds(final long a, final long b) {
        return packBounds(
                Math.min(minX(a), minX(b)), Math.min(minY(a), minY(b)), Math.min(minZ(a), minZ(b)),
                Math.max(maxX(a), maxX(b)), Math.max(maxY(a), maxY(b)), Math.max(maxZ(a), maxZ(b)));
    }

    public static int minX(final long bounds) {
        return (int) bounds & 15;
    }

    public static int minY(final long bounds) {
        return (int) (bounds >>> 4) & 15;
    }

    public static int minZ(final long bounds) {
        return (int) (bounds >>> 8) & 15;
    }

    public static int maxX(final long bounds) {
        return (int) (bounds >>> 12) & 15;
    }

    public static int maxY(final long bounds) {
        return (int) (bounds >>> 16) & 15;
    }

    public static int maxZ(final long bounds) {
        return (int) (bounds >>> 20) & 15;
    }

    public synchronized void offer(final long sectionKey, final long bounds) {
        final long existing = this.writeMap.getOrDefault(sectionKey, -1L);
        this.writeMap.put(sectionKey, existing < 0 ? bounds : unionBounds(existing, bounds));
    }

    /**
     * Drain all entries to the consumer. Single consumer thread only. The
     * lock only covers the buffer swap, not the iteration.
     *
     * @return number of sections drained
     */
    public int drain(final SectionBoundsConsumer action) {
        final Long2LongOpenHashMap snapshot;
        synchronized (this) {
            if (this.writeMap.isEmpty()) {
                return 0;
            }
            snapshot = this.writeMap;
            this.writeMap = this.drainMap;
        }
        final int count = snapshot.size();
        for (final var it = snapshot.long2LongEntrySet().fastIterator(); it.hasNext(); ) {
            final var entry = it.next();
            action.accept(entry.getLongKey(), entry.getLongValue());
        }
        snapshot.clear();
        this.drainMap = snapshot;
        return count;
    }

    public synchronized boolean isEmpty() {
        return this.writeMap.isEmpty();
    }
}
