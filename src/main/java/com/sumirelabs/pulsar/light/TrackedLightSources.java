package com.sumirelabs.pulsar.light;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

/** World-thread identity map with an allocation-free, mutation-safe round-robin cursor. */
final class TrackedLightSources<K, T> {
    private static final class Source<K, T> {
        final K position;
        T value;
        Source<K, T> previous, next;
        Source(K position, T value) { this.position = position; this.value = value; }
    }
    private final Map<K, Source<K, T>> sources = new HashMap<>();
    private Source<K, T> cursor;
    private final Thread owner = Thread.currentThread();

    void update(K position, T source) {
        requireOwner();
        Source<K, T> entry = sources.get(position);
        if (source == null) {
            if (entry == null) return;
            sources.remove(position);
            if (entry.next == entry) cursor = null;
            else {
                entry.previous.next = entry.next;
                entry.next.previous = entry.previous;
                if (cursor == entry) cursor = entry.next;
            }
        } else if (entry != null) entry.value = source;
        else {
            entry = new Source<>(position, source);
            if (cursor == null) { entry.next = entry.previous = entry; cursor = entry; }
            else {
                entry.previous = cursor.previous;
                entry.next = cursor;
                cursor.previous.next = entry;
                cursor.previous = entry;
            }
            sources.put(position, entry);
        }
    }

    boolean isEmpty() { return sources.isEmpty(); }
    int size() { return sources.size(); }

    T get(K position) {
        requireOwner();
        Source<K, T> entry = sources.get(position);
        return entry == null ? null : entry.value;
    }

    boolean matches(K position, T source) {
        requireOwner();
        Source<K, T> entry = sources.get(position);
        return source != null && entry != null && entry.value == source;
    }

    T nextValue() {
        requireOwner();
        if (cursor == null) return null;
        Source<K, T> entry = cursor;
        cursor = entry.next;
        return entry.value;
    }

    void sampleNext(BiConsumer<K, T> sample) {
        requireOwner();
        if (cursor == null) return;
        Source<K, T> entry = cursor;
        // Advance before callbacks, which may remove or replace any source.
        cursor = entry.next;
        sample.accept(entry.position, entry.value);
    }

    void tick(BiPredicate<K, T> stillPresent, Consumer<K> requestSample) {
        requireOwner();
        int remaining = sources.size();
        while (remaining-- > 0 && cursor != null) {
            Source<K, T> entry = cursor;
            cursor = entry.next;
            if (!stillPresent.test(entry.position, entry.value)) update(entry.position, null);
            // Removed/replaced sources also need their previous light corrected.
            requestSample.accept(entry.position);
        }
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Light source tracking requires the world thread");
    }
}
