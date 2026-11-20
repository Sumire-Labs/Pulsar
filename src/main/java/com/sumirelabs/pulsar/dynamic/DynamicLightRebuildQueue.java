package com.sumirelabs.pulsar.dynamic;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Coalesces dirty sections while allowing an in-flight mesh to finish. Stores no light snapshots. */
public final class DynamicLightRebuildQueue {

    public record Section(int x, int y, int z) {
    }
    private final LinkedHashSet<Section> pending = new LinkedHashSet<>();
    public synchronized void add(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        for (int x = minX; x <= maxX; x++)
            for (int y = minY; y <= maxY; y++)
                for (int z = minZ; z <= maxZ; z++)
                    pending.add(new Section(x, y, z));
    }
    public synchronized boolean isEmpty() {
        return pending.isEmpty();
    }
    public synchronized void clear() {
        pending.clear();
    }
    public synchronized void drain(int budget, Predicate<Section> busy, Consumer<Section> submit) {
        var retry = new ArrayList<Section>();
        try {
            var iterator = pending.iterator();
            for (int i = 0; i < budget && iterator.hasNext(); i++) {
                var section = iterator.next();
                iterator.remove();
                try {
                    if (busy.test(section))
                        retry.add(section);
                    else
                        submit.accept(section);
                } catch (RuntimeException failure) {
                    retry.add(section);
                    throw failure;
                }
            }
        } finally {
            pending.addAll(retry);
        }
    }

}
