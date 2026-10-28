package com.sumirelabs.pulsar.light;

import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sparse, per-chunk publication of main-thread light samples. Readers never
 * invoke block callbacks. Each cell publishes its block and fluid together;
 * identity checks prevent a replaced block from inheriting another's sample.
 * The type parameter keeps this concurrency primitive independent of Minecraft.
 */
public final class ContextualLightSnapshot<S> {
    public static final int BLOCK_CHANGED = 1;
    public static final int SKY_CHANGED = 2;
    public static final int BOTH_CHANGED = BLOCK_CHANGED | SKY_CHANGED;
    private record Cell<S>(S block, int blockInfo, S fluid, int fluidInfo) {}

    private volatile ConcurrentHashMap<Integer, Cell<S>> cells;
    private final PendingLightPositions pending = new PendingLightPositions();
    private volatile Set<Integer> missed;
    private final Thread owner = Thread.currentThread();

    public int read(final int position, final S state, final int fallback) {
        final Cell<S> cell = this.cell(position);
        if (cell != null) {
            if (cell.block == state) return cell.blockInfo;
            if (cell.fluid == state) return cell.fluidInfo;
        }
        // A worker can observe a newly placed block before the end-of-tick
        // publication. Use the static value temporarily and request correction.
        this.misses().add(position);
        this.pending.add(position);
        return fallback;
    }

    public void publish(final int position, final S block, final int blockInfo,
                        final S fluid, final int fluidInfo) {
        this.publishChanges(position, block, blockInfo, fluid, fluidInfo, 0);
    }

    private Cell<S> cell(int position) {
        final var current=this.cells;
        return current==null?null:current.get(position);
    }
    private void put(int position,Cell<S> cell) {
        // Only the owning world thread writes cells. The volatile map reference
        // publishes its initialization to readers; values use CHM publication.
        if(this.cells==null)this.cells=new ConcurrentHashMap<>();
        this.cells.put(position,cell);
    }
    private Set<Integer> misses() {
        Set<Integer> current=this.missed;
        if(current!=null)return current;
        synchronized(this) {
            if(this.missed==null)this.missed=ConcurrentHashMap.newKeySet();
            return this.missed;
        }
    }

    /**
     * Seeds a fresh snapshot before it is made worker-visible. Static cells
     * need no entry, change comparison or fallback correction at this stage.
     * Refreshes of a published snapshot must use publishChanges instead.
     */
    void initialize(final int position, final S block, final int blockInfo,
                    final S fluid, final int fluidInfo) {
        if (Thread.currentThread() != this.owner) {
            throw new IllegalStateException("Only the world thread may initialize contextual light");
        }
        if (block != null || fluid != null) {
            this.put(position, new Cell<>(block, blockInfo, fluid, fluidInfo));
        }
    }

    /**
     * Returns the lanes requiring correction; identical samples allocate no new cell.
     * The caller specifies packed fields which affect only block light (emission).
     */
    public int publishChanges(final int position, final S block, final int blockInfo,
                              final S fluid, final int fluidInfo, final int blockOnlyMask) {
        if (Thread.currentThread() != this.owner) {
            throw new IllegalStateException("Only the world thread may publish contextual light");
        }
        final Cell<S> previous = this.cell(position);
        final boolean changed = previous == null ? block != null || fluid != null
                : previous.block != block || previous.fluid != fluid
                || (block != null && previous.blockInfo != blockInfo)
                || (fluid != null && previous.fluidInfo != fluidInfo);
        if (block == null && fluid == null) {
            if (previous != null) this.cells.remove(position);
        } else if (changed) {
            this.put(position, new Cell<>(block, blockInfo, fluid, fluidInfo));
        }
        // A worker may have used a static fallback even when capture returns
        // to the previously sampled state. That miss still needs a correction.
        final Set<Integer> misses=this.missed;
        final boolean correction = misses!=null && !misses.isEmpty() && misses.remove(position);
        if (correction) return BOTH_CHANGED;
        if (!changed) return 0;
        if (previous == null || previous.block != block || previous.fluid != fluid) return BOTH_CHANGED;
        final int changedBits = (block == null ? 0 : previous.blockInfo ^ blockInfo)
                | (fluid == null ? 0 : previous.fluidInfo ^ fluidInfo);
        return (changedBits & ~blockOnlyMask) == 0 ? BLOCK_CHANGED : BOTH_CHANGED;
    }

    public boolean contains(final int position) {
        return this.cell(position)!=null;
    }

    public boolean contains(final int position, final S state) {
        Cell<S> cell=this.cell(position);
        return state!=null && cell!=null && (cell.block==state || cell.fluid==state);
    }

    boolean isEmpty() { return this.cells==null || this.cells.isEmpty(); }

    /** Invalidate only already-published sources in a heightmap-affected column. */
    public void requestColumn(final int localX, final int localZ, final int minY, final int maxY) {
        final var cells=this.cells;
        if (minY > maxY || cells==null || cells.isEmpty()) return;
        final int column = (localX & 15) | ((localZ & 15) << 4);
        // A densely contextual chunk must not pay a whole-chunk scan for each
        // column. Bound work by the smaller of its source count and height span.
        if ((long) maxY - minY + 1L < cells.size()) {
            for (int y = minY;; y++) {
                final int position = (y << 8) | column;
                if (cells.containsKey(position)) this.pending.add(position);
                if (y == maxY) break;
            }
            return;
        }
        for (final int position : cells.keySet()) {
            final int y = position >> 8;
            if ((position & 255) == column && y >= minY && y <= maxY) this.pending.add(position);
        }
    }

    public void request(final int position) {
        this.pending.add(position);
    }

    public boolean hasPending() {
        return !this.pending.isEmpty();
    }

    /** Bounded batch: callback-triggered requests are left for the next tick. */
    public Set<Integer> takePending() {
        return this.takePendingPositions();
    }

    IntSet takePendingPositions() { return this.pending.take(); }
}
