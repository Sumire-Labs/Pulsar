package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.util.CoordinateUtils;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChunkTaskReservationsTest {
    @Test
    void centerComparisonMatchesCellFootprintsAtCoordinateLimitsAndAcrossMultipleJobs() {
        final Object world = new Object();
        final ChunkTaskReservations reservations = new ChunkTaskReservations();
        final java.util.Set<Long> cells = new java.util.HashSet<>();
        final int[][] centers = {{Integer.MAX_VALUE, Integer.MIN_VALUE}, {-400, 120}, {200, -900}};
        for (int[] center : centers) {
            reservations.reserve(world, CoordinateUtils.getChunkKey(center[0], center[1]));
            for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++)
                cells.add(CoordinateUtils.getChunkKey(center[0] + dx, center[1] + dz));
        }
        for (int[] center : centers) {
            for (int z = -8; z <= 8; z++) for (int x = -8; x <= 8; x++) {
                int cx = center[0] + x, cz = center[1] + z;
                boolean available = true;
                for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++)
                    if (cells.contains(CoordinateUtils.getChunkKey(cx + dx, cz + dz))) available = false;
                assertEquals(available, reservations.available(world, CoordinateUtils.getChunkKey(cx, cz)));
            }
        }
        assertThrows(IllegalStateException.class, () -> reservations.release(world, 123L));
        assertEquals(centers.length, reservations.activeJobs(world));
    }

    @Test
    void excludesEveryOverlappingFiveByFiveFootprintIncludingDiagonals() {
        final Object world = new Object();
        final ChunkTaskReservations reservations = new ChunkTaskReservations();
        reservations.reserve(world, CoordinateUtils.getChunkKey(-8, -12));
        for (int z = -5; z <= 5; z++) for (int x = -5; x <= 5; x++) {
            boolean separate = Math.abs(x) >= 5 || Math.abs(z) >= 5;
            assertEquals(separate, reservations.available(world, CoordinateUtils.getChunkKey(-8 + x, -12 + z)),
                    "Offset " + x + "," + z);
        }
        assertThrows(IllegalStateException.class, () -> reservations.reserve(world, CoordinateUtils.getChunkKey(-4, -8)));
        reservations.reserve(world, CoordinateUtils.getChunkKey(-3, -12));
        assertEquals(2, reservations.activeJobs(world));
        reservations.release(world, CoordinateUtils.getChunkKey(-8, -12));
        assertEquals(1, reservations.activeJobs(world));
        reservations.release(world, CoordinateUtils.getChunkKey(-3, -12));
        assertEquals(0, reservations.activeJobs(world));
        assertTrue(reservations.available(world, CoordinateUtils.getChunkKey(-8, -12)));
    }

    @Test
    void worldIdentitySeparatesReservationsEvenWhenEqualityAndDimensionMatch() {
        final Object a = new String("same-world-name"), b = new String("same-world-name");
        final ChunkTaskReservations reservations = new ChunkTaskReservations();
        reservations.reserve(a, 0L);
        assertTrue(reservations.available(b, 0L));
        reservations.reserve(b, 0L);
        reservations.release(a, 0L);
        assertFalse(reservations.available(b, 0L));
        reservations.release(b, 0L);
    }
}
