package com.sumirelabs.pulsar.light;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.function.IntConsumer;

/** Single-writer primitive table. A slot's key never changes after release publication. */
final class PublishedIntTable<V> {
    private static final VarHandle VALUE = MethodHandles.arrayElementVarHandle(Object[].class);
    private static final Object REMOVED = new Object();
    private static final class Table {
        final int[] keys;
        final Object[] values;
        Table(int capacity) { keys = new int[capacity]; values = new Object[capacity]; }
    }
    private volatile Table table = new Table(4);
    private int used;
    private volatile int size;

    private static int hash(int key) {
        key ^= key >>> 16;
        key *= 0x7feb352d;
        key ^= key >>> 15;
        return key;
    }

    @SuppressWarnings("unchecked")
    V get(int key) {
        Table current = table;
        int mask = current.values.length - 1;
        for (int slot = hash(key) & mask;; slot = (slot + 1) & mask) {
            Object value = VALUE.getAcquire(current.values, slot);
            if (value == null) return null;
            // Acquire the value before reading its immutable key. Deleted slots
            // keep both their key and their place in the probe chain.
            if (current.keys[slot] == key) return value == REMOVED ? null : (V) value;
        }
    }

    void put(int key, V value) {
        if (value == null) throw new NullPointerException("value");
        Table current = table;
        if ((used + 1) * 4 >= current.values.length * 3) {
            current = rebuild(size * 2 < current.values.length ? current.values.length : current.values.length * 2);
        }
        int mask = current.values.length - 1;
        for (int slot = hash(key) & mask;; slot = (slot + 1) & mask) {
            Object previous = current.values[slot];
            if (previous == null) {
                current.keys[slot] = key;
                VALUE.setRelease(current.values, slot, value);
                used++; size++;
                return;
            }
            if (current.keys[slot] == key) {
                VALUE.setRelease(current.values, slot, value);
                if (previous == REMOVED) size++;
                return;
            }
        }
    }

    void remove(int key) {
        Table current = table;
        int mask = current.values.length - 1;
        for (int slot = hash(key) & mask;; slot = (slot + 1) & mask) {
            Object previous = current.values[slot];
            if (previous == null) return;
            if (current.keys[slot] == key) {
                if (previous != REMOVED) { VALUE.setRelease(current.values, slot, REMOVED); size--; }
                return;
            }
        }
    }

    private Table rebuild(int capacity) {
        Table old = table, next = new Table(capacity);
        for (int i = 0; i < old.values.length; i++) {
            Object value = old.values[i];
            if (value == null || value == REMOVED) continue;
            int slot = hash(old.keys[i]) & (capacity - 1);
            while (next.values[slot] != null) slot = (slot + 1) & (capacity - 1);
            next.keys[slot] = old.keys[i]; next.values[slot] = value;
        }
        used = size;
        table = next;
        return next;
    }

    int size() { return size; }
    boolean isEmpty() { return size == 0; }

    void forEachKey(IntConsumer consumer) {
        Table current = table;
        for (int i = 0; i < current.values.length; i++) {
            Object value = VALUE.getAcquire(current.values, i);
            if (value != null && value != REMOVED) consumer.accept(current.keys[i]);
        }
    }
}
