package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.util.CoordinateUtils;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChunkTaskReservationsTest {
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
