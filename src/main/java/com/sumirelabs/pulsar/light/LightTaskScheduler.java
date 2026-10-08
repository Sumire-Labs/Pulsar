package com.sumirelabs.pulsar.light;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.LongPredicate;

/** Weighted turns survive budget yields: four edits, one initial light, one maintenance task. */
final class LightTaskScheduler {
    private final LightQueue queue;
    private final LongSupplier clock;
    private int turn;

    LightTaskScheduler(final LightQueue queue, final LongSupplier clock) {
        this.queue = queue;
        this.clock = clock;
    }

    private ChunkTasks nextTask() {
        return this.claimAvailable(key -> true);
    }

    /** The shared dispatcher serializes priority turns and task claims. */
    ChunkTasks claimAvailable(final LongPredicate allowed) {
        boolean editsUnavailable = false;
        for (int tried = 0; tried < 6; tried++) {
            final int slot = this.turn;
            this.turn = (this.turn + 1) % 6;
            // A failed edit scan already checked every candidate against the
            // current reservations. Preserve weighted turns without rescanning.
            if (slot < 4 && editsUnavailable) continue;
            final ChunkTasks task = slot < 4 ? this.queue.removeFirstBlockChangeTask(allowed)
                    : slot == 4 ? this.queue.removeFirstInitialLightTask(allowed)
                    : this.queue.removeFirstMaintenanceTask(allowed);
            if (task != null) return task;
            if (slot < 4) editsUnavailable = true;
        }
        return null;
    }

    /** A task is atomic; the deadline is checked before starting each subsequent task. */
    boolean drainOneUntil(final long deadline, final BooleanSupplier running,
                          final Consumer<ChunkTasks> processor) {
        if (!running.getAsBoolean() || this.clock.getAsLong() - deadline >= 0L) return false;
        final ChunkTasks task = this.nextTask();
        if (task == null) return false;
        try {
            processor.accept(task);
        } finally {
            this.queue.completeTask(task);
        }
        return true;
    }

    boolean drainUntil(final long deadline, final BooleanSupplier running,
                       final Consumer<ChunkTasks> processor) {
        while (this.drainOneUntil(deadline, running, processor)) {
            // The single-task path checks the deadline before each turn.
        }
        return running.getAsBoolean() && this.queue.hasWork();
    }
}
