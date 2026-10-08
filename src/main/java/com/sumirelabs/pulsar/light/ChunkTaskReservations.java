package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.util.CoordinateUtils;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.IdentityHashMap;

/** Access is serialized by the dispatcher, independently of queue/chunk monitors. */
final class ChunkTaskReservations {
    private final IdentityHashMap<Object, LongOpenHashSet> occupied = new IdentityHashMap<>();

    boolean available(Object world, long center) {
        LongOpenHashSet cells = occupied.get(world);
        if (cells == null) return true;
        int cx = CoordinateUtils.getChunkX(center), cz = CoordinateUtils.getChunkZ(center);
        for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++)
            if (cells.contains(CoordinateUtils.getChunkKey(cx + dx, cz + dz))) return false;
        return true;
    }

    void reserve(Object world, long center) {
        if (!available(world, center)) throw new IllegalStateException("Overlapping lighting jobs");
        LongOpenHashSet cells = occupied.computeIfAbsent(world, ignored -> new LongOpenHashSet());
        int cx = CoordinateUtils.getChunkX(center), cz = CoordinateUtils.getChunkZ(center);
        for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++)
            cells.add(CoordinateUtils.getChunkKey(cx + dx, cz + dz));
    }

    void release(Object world, long center) {
        LongOpenHashSet cells = occupied.get(world);
        if (cells == null) throw new IllegalStateException("Lighting reservation missing");
        int cx = CoordinateUtils.getChunkX(center), cz = CoordinateUtils.getChunkZ(center);
        for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++)
            cells.remove(CoordinateUtils.getChunkKey(cx + dx, cz + dz));
        if (cells.isEmpty()) occupied.remove(world);
    }

    int activeJobs(Object world) {
        LongOpenHashSet cells = occupied.get(world);
        return cells == null ? 0 : cells.size() / 25;
    }
}
