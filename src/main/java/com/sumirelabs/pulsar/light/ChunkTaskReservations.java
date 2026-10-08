package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.util.CoordinateUtils;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.IdentityHashMap;

/** Access is serialized by the dispatcher, independently of queue/chunk monitors. */
final class ChunkTaskReservations {
    // At most 16 jobs share the process-wide pool. Compare their centers,
    // instead of hashing 25 cells per candidate and retaining 25 per job.
    private final IdentityHashMap<Object, LongArrayList> occupied = new IdentityHashMap<>();

    boolean available(Object world, long center) {
        LongArrayList centers = occupied.get(world);
        if (centers == null) return true;
        int cx = CoordinateUtils.getChunkX(center), cz = CoordinateUtils.getChunkZ(center);
        for (int i = 0; i < centers.size(); i++) {
            long other = centers.getLong(i);
            // Subtract as int before widening: preserve packed coordinate wrap
            // at integer limits, just like the original 5x5 cell reservation.
            long dx = cx - CoordinateUtils.getChunkX(other);
            long dz = cz - CoordinateUtils.getChunkZ(other);
            if (Math.abs(dx) <= 4 && Math.abs(dz) <= 4) return false;
        }
        return true;
    }

    void reserve(Object world, long center) {
        if (!available(world, center)) throw new IllegalStateException("Overlapping lighting jobs");
        occupied.computeIfAbsent(world, ignored -> new LongArrayList()).add(center);
    }

    void release(Object world, long center) {
        LongArrayList centers = occupied.get(world);
        if (centers == null || !centers.rem(center)) throw new IllegalStateException("Lighting reservation missing");
        if (centers.isEmpty()) occupied.remove(world);
    }

    int activeJobs(Object world) {
        LongArrayList centers = occupied.get(world);
        return centers == null ? 0 : centers.size();
    }
}
