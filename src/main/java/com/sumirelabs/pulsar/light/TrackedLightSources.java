package com.sumirelabs.pulsar.light;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/** Sparse world-thread-only identity tracking; sampling is deferred until after iteration. */
final class TrackedLightSources<K, T> {
    private final Map<K, T> sources = new HashMap<>();
    private final Thread owner = Thread.currentThread();

    void update(K position, T source) {
        requireOwner();
        if (source == null) sources.remove(position);
        else sources.put(position, source);
    }

    boolean isEmpty() { return sources.isEmpty(); }

    boolean matches(K position, T source) {
        requireOwner();
        return source != null && sources.get(position) == source;
    }

    void tick(BiPredicate<K, T> stillPresent, Consumer<K> requestSample) {
        requireOwner();
        var iterator = sources.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!stillPresent.test(entry.getKey(), entry.getValue())) iterator.remove();
            // Removed/replaced sources also need their previous light corrected.
            requestSample.accept(entry.getKey());
        }
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Light source tracking requires the world thread");
    }
}
