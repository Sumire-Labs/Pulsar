package com.sumirelabs.pulsar.light;

import java.util.Arrays;
import java.util.function.LongConsumer;

/**
 * Queue for section coordinates that need render updates. Worker threads
 * (sky + block) offer packed coordinates; the main thread drains them in
 * batch.
 *
 * <p>Double-buffered: drain swaps in a pre-allocated buffer so producers
 * aren't blocked during iteration. Growable to handle chunk loading bursts.
 */
public final class RenderUpdateQueue {

    private long[] writeBuffer;
    private int writeSize;
    private long[] drainBuffer;

    public RenderUpdateQueue() {
        this(4096);
    }

    public RenderUpdateQueue(final int initialCapacity) {
        this.writeBuffer = new long[initialCapacity];
        this.drainBuffer = new long[initialCapacity];
    }

    public synchronized void offer(final long value) {
        if (this.writeSize == this.writeBuffer.length) {
            this.writeBuffer = Arrays.copyOf(this.writeBuffer, this.writeBuffer.length * 2);
        }
        this.writeBuffer[this.writeSize++] = value;
    }

    /**
     * Drain all entries to the consumer. Single consumer thread only. The
     * lock only covers the buffer swap, not the iteration.
     *
     * @return number of entries drained
     */
    public int drain(final LongConsumer action) {
        final long[] snapshot;
        final int count;
        synchronized (this) {
            count = this.writeSize;
            if (count == 0) return 0;
            snapshot = this.writeBuffer;
            // Swap in the drain buffer for producers; grow if needed
            this.writeBuffer = this.drainBuffer.length >= count ? this.drainBuffer : new long[snapshot.length];
            this.writeSize = 0;
        }
        for (int i = 0; i < count; i++) {
            action.accept(snapshot[i]);
        }
        // Recycle for next drain
        this.drainBuffer = snapshot;
        return count;
    }

    public synchronized boolean isEmpty() {
        return this.writeSize == 0;
    }
}
