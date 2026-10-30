package com.sumirelabs.pulsar.light;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;

/** Thread-confined scratch buffers: retain a small hot set, reclaim burst capacity at GC. */
final class WorkingBytesPool implements Iterable<byte[]> {
    static final int HOT_LIMIT = 64;
    private final ArrayDeque<byte[]> hot = new ArrayDeque<>();
    private WeakReference<ArrayDeque<byte[]>> cold;
    private final int limit;

    WorkingBytesPool(int limit) { this.limit = limit; }
    private ArrayDeque<byte[]> cold() { return cold == null ? null : cold.get(); }

    byte[] pollFirst() {
        byte[] bytes = hot.pollFirst();
        if (bytes != null) return bytes;
        var overflow = cold();
        return overflow == null ? null : overflow.pollFirst();
    }

    void addFirst(byte[] bytes) {
        if (hot.size() < Math.min(HOT_LIMIT, limit)) { hot.addFirst(bytes); return; }
        var overflow = cold();
        if (overflow == null) {
            overflow = new ArrayDeque<>();
            cold = new WeakReference<>(overflow);
        }
        if (hot.size() + overflow.size() < limit) overflow.addFirst(bytes);
    }

    int size() { var overflow = cold(); return hot.size() + (overflow == null ? 0 : overflow.size()); }
    void clear() { hot.clear(); cold = null; }

    /** Diagnostic iteration pins a snapshot; normal acquire/release never copies the pool. */
    public Iterator<byte[]> iterator() {
        var snapshot = new ArrayList<byte[]>(hot);
        var overflow = cold();
        if (overflow != null) snapshot.addAll(overflow);
        return snapshot.iterator();
    }
}
