package com.sumirelabs.pulsar.light;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sparse, per-chunk publication of main-thread light samples. Readers never
 * invoke block callbacks. Each cell publishes its block and fluid together;
 * identity checks prevent a replaced block from inheriting another's sample.
 * The type parameter keeps this concurrency primitive independent of Minecraft.
 */
public final class ContextualLightSnapshot<S> {
    private record Cell<S>(S block, int blockInfo, S fluid, int fluidInfo) {}

    private final ConcurrentHashMap<Integer, Cell<S>> cells = new ConcurrentHashMap<>();
    private final Set<Integer> pending = ConcurrentHashMap.newKeySet();
    private final Thread owner = Thread.currentThread();

    public int read(final int position, final S state, final int fallback) {
        final Cell<S> cell = this.cells.get(position);
        if (cell != null) {
            if (cell.block == state) return cell.blockInfo;
            if (cell.fluid == state) return cell.fluidInfo;
        }
        // A worker can observe a newly placed block before the end-of-tick
        // publication. Use the static value temporarily and request correction.
        this.pending.add(position);
        return fallback;
    }

    public void publish(final int position, final S block, final int blockInfo,
                        final S fluid, final int fluidInfo) {
        if (Thread.currentThread() != this.owner) {
            throw new IllegalStateException("Only the world thread may publish contextual light");
        }
        if (block == null && fluid == null) {
            this.cells.remove(position);
        } else {
            this.cells.put(position, new Cell<>(block, blockInfo, fluid, fluidInfo));
        }
    }

    public boolean contains(final int position) {
        return this.cells.containsKey(position);
    }

    public void request(final int position) {
        this.pending.add(position);
    }

    public boolean hasPending() {
        return !this.pending.isEmpty();
    }

    /** Bounded batch: callback-triggered requests are left for the next tick. */
    public Set<Integer> takePending() {
        final Set<Integer> batch = new HashSet<>(this.pending);
        for (final int position : batch) this.pending.remove(position);
        return batch;
    }
}
