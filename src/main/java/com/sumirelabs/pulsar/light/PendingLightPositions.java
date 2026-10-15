package com.sumirelabs.pulsar.light;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;

/** Short producer critical sections; detached batches never hold the monitor during callbacks. */
final class PendingLightPositions {
    private IntOpenHashSet positions;
    private volatile boolean empty = true;

    synchronized void add(final int position) {
        if (this.positions == null) this.positions = new IntOpenHashSet(4);
        this.positions.add(position);
        if (this.empty) this.empty = false;
    }

    boolean isEmpty() { return this.empty; }

    synchronized IntSet take() {
        if (this.empty) return IntSets.EMPTY_SET;
        final IntOpenHashSet batch = this.positions;
        this.positions = null;
        this.empty = true;
        return batch;
    }
}
