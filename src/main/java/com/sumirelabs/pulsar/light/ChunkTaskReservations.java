package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.util.CoordinateUtils;
import com.sumirelabs.pulsar.config.PulsarConfig;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.IdentityHashMap;

/** Read 5x5 metadata, write 3x3 light/topology; dispatcher serializes reservation bookkeeping. */
final class ChunkTaskReservations {
    private final int conflictDistance;
    // Two read halos may overlap. Writes must remain disjoint from both the
    // other job's read halo and its writes: read radius 2 + write radius 1.
    ChunkTaskReservations() { this(PulsarConfig.performance.conservativeLightTaskReservations ? 4 : 3); }
    // Retain the previous policy as a counterfactual for integration benchmarks.
    ChunkTaskReservations(int conflictDistance) {
        if (conflictDistance < 3 || conflictDistance > 4) throw new IllegalArgumentException("Invalid footprint");
        this.conflictDistance = conflictDistance;
    }
    // At most 16 jobs share the process-wide pool. Compare their centers,
    // instead of retaining per-cell reader/writer bookkeeping for every job.
    private final IdentityHashMap<Object, LongArrayList> occupied = new IdentityHashMap<>();

    boolean available(Object world, long center) {
        LongArrayList centers = occupied.get(world);
        if (centers == null) return true;
        int cx = CoordinateUtils.getChunkX(center), cz = CoordinateUtils.getChunkZ(center);
        for (int i = 0; i < centers.size(); i++) {
            long other = centers.getLong(i);
            // Subtract as int before widening: preserve packed coordinate wrap
            // at integer limits, just like explicit per-cell reservations.
            long dx = cx - CoordinateUtils.getChunkX(other);
            long dz = cz - CoordinateUtils.getChunkZ(other);
            if (Math.abs(dx) <= conflictDistance && Math.abs(dz) <= conflictDistance) return false;
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
