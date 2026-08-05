package com.sumirelabs.pulsar.light;

/**
 * Generation-aware, exactly-once state machine for a chunk's initial light.
 * Sky and block workers may finish concurrently, so each required lane is
 * accepted once and only the current generation can reach completion.
 */
final class InitialLightCompletionState {

    static final int SKY = 1;
    static final int BLOCK = 1 << 1;

    enum Result {
        IGNORED,
        WAITING,
        COMPLETE
    }

    private final long generation;
    private final int requiredLanes;
    private int completedLanes;

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

    synchronized Result complete(final long taskGeneration, final int lane) {
        if (lane != SKY && lane != BLOCK) {
            throw new IllegalArgumentException("Invalid initial-light lane: " + lane);
        }
        if (taskGeneration != this.generation || (lane & this.requiredLanes) == 0) {
            return Result.IGNORED;
        }
        if ((this.completedLanes & lane) != 0) {
            return Result.IGNORED;
        }
        this.completedLanes |= lane;
        return this.completedLanes == this.requiredLanes ? Result.COMPLETE : Result.WAITING;
    }
}
