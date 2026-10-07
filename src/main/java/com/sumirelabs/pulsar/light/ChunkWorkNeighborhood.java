package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.util.CoordinateUtils;

import java.util.concurrent.Future;
import java.util.function.LongFunction;

/** Finds queued lighting work that may mutate a chunk through the 3x3 engine cache. */
final class ChunkWorkNeighborhood {

    private ChunkWorkNeighborhood() {
    }

    static Future<Void> findPendingFuture(final int chunkX, final int chunkZ,
                                         final LongFunction<Future<Void>> futureAt) {
        for (int dz = -1; dz <= 1; ++dz) {
            for (int dx = -1; dx <= 1; ++dx) {
                final Future<Void> future = futureAt.apply(
                        CoordinateUtils.getChunkKey(chunkX + dx, chunkZ + dz));
                if (future != null) {
                    return future;
                }
            }
        }
        return null;
    }
}
