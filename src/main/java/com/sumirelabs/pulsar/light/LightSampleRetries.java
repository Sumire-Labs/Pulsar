package com.sumirelabs.pulsar.light;

import java.util.HashMap;
import java.util.function.IntConsumer;

/** Owner-thread exponential retry, bounded to one attempt per 32 ticks per failed cell.
 * Allocated only on callback failure; successful samples discard their entries.
 */
public final class LightSampleRetries {
    private record Retry(int attempts, long due) {}
    private final HashMap<Integer, Retry> cells = new HashMap<>();
    public void failed(int key, long tick) {
        Retry previous = cells.get(key);
        int attempts = previous == null ? 0 : Math.min(5, previous.attempts + 1);
        cells.put(key, new Retry(attempts, tick + (1L << attempts)));
    }
    public void succeeded(int key) { cells.remove(key); }
    public boolean isEmpty() { return cells.isEmpty(); }
    public boolean waiting(int key, long tick) { Retry retry=cells.get(key); return retry!=null && tick<retry.due; }
    public void ready(long tick, IntConsumer consumer) {
        for (var cell : cells.entrySet()) if (tick >= cell.getValue().due) consumer.accept(cell.getKey());
    }
}
