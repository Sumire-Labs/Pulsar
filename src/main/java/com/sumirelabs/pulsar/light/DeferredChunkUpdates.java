package com.sumirelabs.pulsar.light;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/** Server-thread retry ownership; a chunk replacement must not inherit an old send. */
final class DeferredChunkUpdates<T> {
    enum Decision { WAIT, SEND, DROP }

    private final Long2ObjectOpenHashMap<T> pending = new Long2ObjectOpenHashMap<>();

    void defer(long key, T chunk) {
        pending.put(key, chunk);
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

    void drain(BiFunction<Long, T, Decision> decision, BiConsumer<Long, T> send) {
        if (pending.isEmpty()) {
            return;
        }

        // Snapshot primitive keys and values before callbacks: callbacks can replace,
        // remove, or re-defer entries, and additions must wait for the next drain.
        // Keep these arrays local so a nested drain cannot overwrite its caller's snapshot.
        int size = pending.size();
        long[] keys = new long[size];
        Object[] chunks = new Object[size];
        var iterator = pending.long2ObjectEntrySet().fastIterator();
        for (int i = 0; i < size; i++) {
            var entry = iterator.next();
            keys[i] = entry.getLongKey();
            chunks[i] = entry.getValue();
        }

        for (int i = 0; i < size; i++) {
            long key = keys[i];
            @SuppressWarnings("unchecked") T chunk = (T) chunks[i];
            if (pending.get(key) != chunk) {
                continue;
            }
            Decision action = decision.apply(key, chunk);
            if (action == Decision.WAIT) {
                continue;
            }
            pending.remove(key);
            if (action == Decision.SEND) {
                send.accept(key, chunk);
            }
        }
    }
}
