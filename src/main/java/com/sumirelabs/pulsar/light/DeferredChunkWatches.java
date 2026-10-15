package com.sumirelabs.pulsar.light;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/** World-thread pending initial packets; waiting players do not receive entities or partial updates. */
final class DeferredChunkWatches<E, C, P> {
    private record Batch<C, P>(C chunk, LinkedHashSet<P> players) {}
    private final Map<E, Batch<C, P>> pending = new LinkedHashMap<>();

    void defer(E entry, C chunk, P player) {
        Batch<C, P> batch = this.pending.get(entry);
        if (batch == null || batch.chunk != chunk) {
            batch = new Batch<>(chunk, new LinkedHashSet<>());
            this.pending.put(entry, batch);
        }
        batch.players.add(player);
    }

    void cancel(E entry, P player) {
        Batch<C, P> batch = this.pending.get(entry);
        if (batch != null && batch.players.remove(player) && batch.players.isEmpty()) this.pending.remove(entry);
    }

    void drain(BiPredicate<E, C> valid, BiPredicate<E, C> ready,
               Predicate<P> connected, BiConsumer<E, P> add) {
        if (this.pending.isEmpty()) return;
        for (E entry : new java.util.ArrayList<>(this.pending.keySet())) {
            Batch<C, P> batch = this.pending.get(entry);
            if (batch == null) continue;
            if (!valid.test(entry, batch.chunk)) { this.pending.remove(entry); continue; }
            batch.players.removeIf(player -> !connected.test(player));
            if (batch.players.isEmpty()) { this.pending.remove(entry); continue; }
            if (!ready.test(entry, batch.chunk)) continue;
            for (P player : new java.util.ArrayList<>(batch.players)) {
                if (this.pending.get(entry) != batch) break;
                // A callback can cancel another player or enqueue this player again.
                if (batch.players.remove(player) && connected.test(player)) add.accept(entry, player);
            }
            if (this.pending.get(entry) == batch && batch.players.isEmpty()) this.pending.remove(entry);
        }
    }

    void clear() { this.pending.clear(); }
    boolean isEmpty() { return this.pending.isEmpty(); }
    boolean contains(E entry, P player) {
        Batch<C, P> batch = this.pending.get(entry);
        return batch != null && batch.players.contains(player);
    }
}
