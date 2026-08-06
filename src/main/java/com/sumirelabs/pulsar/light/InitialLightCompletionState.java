package com.sumirelabs.pulsar.light;

/**
 * Generation-aware, exactly-once state machine for a chunk's initial light.
 * Sky and block workers may finish concurrently. Each required lane must
 * finish both its initial propagation and its deferred edge reconciliation
 * before the generation can be published as light-ready.
 */
final class InitialLightCompletionState {

    static final int SKY = 1;
    static final int BLOCK = 1 << 1;
    private final long generation;
    private final int requiredLanes;
    private int initialCompletedLanes;
    private int edgeCompletedLanes;
    InitialLightCompletionState(final long generation, final int requiredLanes) {
        if (generation <= 0L) {
            throw new IllegalArgumentException("Initial-light generation must be positive");
        }
        if (requiredLanes == 0 || (requiredLanes & ~(SKY | BLOCK)) != 0) {
            throw new IllegalArgumentException("Invalid initial-light lane mask: " + requiredLanes);
        }
        this.generation = generation;
        this.requiredLanes = requiredLanes;
    }

    synchronized Result completeInitial(final long taskGeneration, final int lane) {
        if (!this.accepts(taskGeneration, lane) || (this.initialCompletedLanes & lane) != 0) {
            return Result.IGNORED;
        }
        this.initialCompletedLanes |= lane;
        return this.initialCompletedLanes == this.requiredLanes ? Result.INITIAL_COMPLETE : Result.WAITING;
    }

    synchronized Result completeEdges(final long taskGeneration, final int lane) {
        if (!this.accepts(taskGeneration, lane)
                || this.initialCompletedLanes != this.requiredLanes
                || (this.edgeCompletedLanes & lane) != 0) {
            return Result.IGNORED;
        }
        this.edgeCompletedLanes |= lane;
        return this.edgeCompletedLanes == this.requiredLanes ? Result.COMPLETE : Result.WAITING;
    }

    private boolean accepts(final long taskGeneration, final int lane) {
        if (lane != SKY && lane != BLOCK) {
            throw new IllegalArgumentException("Invalid initial-light lane: " + lane);
        }
        return taskGeneration == this.generation && (lane & this.requiredLanes) != 0;
    }

    enum Result {
        IGNORED,
        WAITING,
        INITIAL_COMPLETE,
        COMPLETE
    }
}
