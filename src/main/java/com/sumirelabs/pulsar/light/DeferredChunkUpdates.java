/*
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.sumirelabs.pulsar.light;

import java.util.Arrays;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/** Server-thread retry ownership; a chunk replacement must not inherit an old send. */
final class DeferredChunkUpdates<T> {

    enum Decision {
        WAIT, SEND, DROP
    }

    interface Check<T> {

        Decision apply(long key, T value);

    }

    interface Send<T> {

        void accept(long key, T value);

    }

    // Bound retained scratch space per queue after a large generation burst.
    private static final int MAX_CACHED_ENTRIES = 1024;
    private record Snapshot(long[] keys, Object[] values) {

        Snapshot(int size) {
            this(new long[size], new Object[size]);
        }

    }

    private final Long2ObjectOpenHashMap<T> pending = new Long2ObjectOpenHashMap<>();
    private Snapshot spare;

    void defer(long key, T chunk) {
        pending.put(key, chunk);
    }

    T get(long key) {
        return pending.get(key);
    }

    void remove(long key) {
        pending.remove(key);
    }

    void clear() {
        pending.clear();
    }

    int size() {
        return pending.size();
    }

    void drain(Check<T> decision, Send<T> send) {
        if (pending.isEmpty()) {
            return;
        }

        // Snapshot primitive keys and values before callbacks: callbacks can replace,
        // remove, or re-defer entries, and additions must wait for the next drain.
        int size = pending.size();
        Snapshot snapshot = spare;
        spare = null; // A nested drain needs its own snapshot.
        if (snapshot == null || snapshot.keys.length < size)
            snapshot = new Snapshot(size);
        try {
            drain(snapshot, size, decision, send);
        } finally {
            if (snapshot.keys.length <= MAX_CACHED_ENTRIES) {
                Arrays.fill(snapshot.values, 0, size, null);
                spare = snapshot;
            }
        }
    }

    private void drain(Snapshot snapshot, int size, Check<T> decision, Send<T> send) {
        long[] keys = snapshot.keys;
        Object[] chunks = snapshot.values;
        var iterator = pending.long2ObjectEntrySet().fastIterator();
        for (int i = 0; i < size; i++) {
            var entry = iterator.next();
            keys[i] = entry.getLongKey();
            chunks[i] = entry.getValue();
        }

        for (int i = 0; i < size; i++) {
            long key = keys[i];
            @SuppressWarnings("unchecked")
            T chunk = (T) chunks[i];
            if (pending.get(key) != chunk) {
                continue;
            }
            Decision action = decision.apply(key, chunk);
            if (action == Decision.WAIT || pending.get(key) != chunk) {
                continue;
            }
            pending.remove(key);
            if (action == Decision.SEND) {
                send.accept(key, chunk);
            }
        }
    }

}
