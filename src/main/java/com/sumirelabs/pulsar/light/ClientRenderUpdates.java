package com.sumirelabs.pulsar.light;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.function.BooleanSupplier;

/** Main-thread render notifications, merged across both light lanes within a tick. */
final class ClientRenderUpdates<T> {
    @FunctionalInterface interface Lookup<T> { T get(long chunkKey); }
    @FunctionalInterface interface Mark { void accept(long chunkKey, int sectionY, long bounds); }

    private static final class Entry<T> {
        final T chunk;
        final Int2ObjectOpenHashMap<LongArrayList> sections = new Int2ObjectOpenHashMap<>();
        Entry(T chunk) { this.chunk = chunk; }
    }

    private Long2ObjectOpenHashMap<Entry<T>> pending = new Long2ObjectOpenHashMap<>();
    private Long2ObjectOpenHashMap<Entry<T>> spare = new Long2ObjectOpenHashMap<>();
    private boolean draining;
    private long clearGeneration;

    void add(long key, T chunk, int sectionY, long bounds) {
        Entry<T> entry = pending.get(key);
        if (entry == null || entry.chunk != chunk) {
            entry = new Entry<>(chunk);
            pending.put(key, entry);
        }
        LongArrayList ranges = entry.sections.get(sectionY);
        if (ranges == null) {
            ranges = new LongArrayList(1);
            entry.sections.put(sectionY, ranges);
        }
        for (int i = 0; i < ranges.size(); i++) {
            long previous = ranges.getLong(i);
            long combined = RenderBounds.union(previous, bounds);
            // A bounding box can create NEW neighboring mesh rebuilds. Merge
            // only when vanilla's one-block inflation touches the same sections.
            if (sectionMask(combined) == (sectionMask(previous) | sectionMask(bounds))) {
                ranges.set(i, combined);
                return;
            }
        }
        ranges.add(bounds);
    }

    /** 3x3x3 renderer-section coverage after vanilla's one-block inflation. */
    static int sectionMask(long bounds) {
        int mask = 0;
        for (int y = RenderBounds.minY(bounds) == 0 ? 0 : 1;
             y <= (RenderBounds.maxY(bounds) == 15 ? 2 : 1); y++) {
            for (int z = RenderBounds.minZ(bounds) == 0 ? 0 : 1;
                 z <= (RenderBounds.maxZ(bounds) == 15 ? 2 : 1); z++) {
                for (int x = RenderBounds.minX(bounds) == 0 ? 0 : 1;
                     x <= (RenderBounds.maxX(bounds) == 15 ? 2 : 1); x++) {
                    mask |= 1 << (x + z * 3 + y * 9);
                }
            }
        }
        return mask;
    }

    void remove(long key) {
        pending.remove(key);
        if (!draining) spare.remove(key);
    }
    void clear() {
        pending.clear();
        clearGeneration++;
        // A renderer callback may clear the queue. Do not invalidate its iterator.
        if (!draining) spare.clear();
    }

    int drain(Lookup<T> loaded, Mark mark) {
        return drainWhile(loaded, mark, () -> true);
    }

    /** Unsent ranges remain in the old batch; callback additions stay in pending. */
    int drainWhile(Lookup<T> loaded, Mark mark, BooleanSupplier withinBudget) {
        if (draining || (pending.isEmpty() && spare.isEmpty()) || !withinBudget.getAsBoolean()) return 0;
        draining = true;
        if (spare.isEmpty()) {
            Long2ObjectOpenHashMap<Entry<T>> batch = pending;
            pending = spare;
            spare = batch;
        }
        int marks = 0;
        final long generation = clearGeneration;
        try {
            var chunks = spare.long2ObjectEntrySet().fastIterator();
            while (chunks.hasNext()) {
                var chunkEntry = chunks.next();
                long key = chunkEntry.getLongKey();
                Entry<T> entry = chunkEntry.getValue();
                var sections = entry.sections.int2ObjectEntrySet().fastIterator();
                while (sections.hasNext()) {
                    var section = sections.next();
                    LongArrayList ranges = section.getValue();
                    while (!ranges.isEmpty()) {
                        if (loaded.get(key) != entry.chunk) {
                            ranges.clear();
                            break;
                        }
                        if (!withinBudget.getAsBoolean()) return marks;
                        mark.accept(key, section.getIntKey(), ranges.getLong(0));
                        ranges.removeLong(0);
                        marks++;
                        if (generation != clearGeneration) return marks;
                    }
                    sections.remove();
                }
                chunks.remove();
            }
        } finally {
            if (generation != clearGeneration) spare.clear();
            draining = false;
        }
        return marks;
    }
}
