package com.sumirelabs.pulsar.light;

/** Chooses when a dense skylight edit needs a full rebuild within the sky lane. */
public final class BulkSkyRelightPolicy {

    public static final int THRESHOLD = 16;

    private BulkSkyRelightPolicy() {
    }

    public static boolean shouldPromote(final boolean coordinatedTask,
                                        final int changedPositionCount) {
        return !coordinatedTask && changedPositionCount >= THRESHOLD;
    }
}
