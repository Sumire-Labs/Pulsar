package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.util.CoordinateUtils;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ChunkWorkNeighborhoodTest {

    @Test
    void findsPendingWorkInEveryChunkThatCanShareTheEngineCache() {
        final int chunkX = -17;
        final int chunkZ = 29;
        for (int dz = -1; dz <= 1; ++dz) {
            for (int dx = -1; dx <= 1; ++dx) {
                final Future<Void> pending = new CompletableFuture<>();
                final long pendingKey = CoordinateUtils.getChunkKey(chunkX + dx, chunkZ + dz);
                assertSame(pending, ChunkWorkNeighborhood.findPendingFuture(chunkX, chunkZ,
                        key -> key == pendingKey ? pending : null),
                        "missed pending chunk offset (" + dx + ", " + dz + ")");
            }
        }
    }

    @Test
    void noPendingWorkChecksExactlyTheSurroundingNineChunkKeys() {
        final HashSet<Long> checkedKeys = new HashSet<>();
        assertNull(ChunkWorkNeighborhood.findPendingFuture(0, 0, key -> {
            checkedKeys.add(key);
            return null;
        }));

        final HashSet<Long> expected = new HashSet<>();
        for (int dz = -1; dz <= 1; ++dz) {
            for (int dx = -1; dx <= 1; ++dx) {
                expected.add(CoordinateUtils.getChunkKey(dx, dz));
            }
        }
        assertEquals(expected, checkedKeys);
    }
}
