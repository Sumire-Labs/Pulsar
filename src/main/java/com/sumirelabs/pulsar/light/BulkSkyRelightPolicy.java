package com.sumirelabs.pulsar.light;

import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

/**
 * Chooses when a dense skylight edit needs a full rebuild within the sky lane.
 */
public final class BulkSkyRelightPolicy {

    public static final int THRESHOLD = 16;

    private BulkSkyRelightPolicy() {
    }

    /** Keep single-column batches on the column-aware incremental path. */
    public static boolean shouldPromoteColumns(final boolean coordinatedTask,
                                        final IntOpenHashSet changedPositions) {
        if (!shouldPromote(coordinatedTask, changedPositions == null ? 0 : changedPositions.size())) {
            return false;
        }
        final IntIterator iterator = changedPositions.iterator();
        final int column = iterator.nextInt() & 255;
        while (iterator.hasNext()) {
            if ((iterator.nextInt() & 255) != column) {
                return true;
            }
        }
        return false;
    }

    public static boolean shouldPromote(final boolean coordinatedTask,
                                        final int changedPositionCount) {
        return !coordinatedTask && changedPositionCount >= THRESHOLD;
    }
}
