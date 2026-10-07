package com.sumirelabs.pulsar.light;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

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
        for (int tried = 0; tried < 6; tried++) {
            final int slot = this.turn;
            this.turn = (this.turn + 1) % 6;
            final ChunkTasks task = slot < 4 ? this.queue.removeFirstBlockChangeTask()
                    : slot == 4 ? this.queue.removeFirstInitialLightTask()
                    : this.queue.removeFirstMaintenanceTask();
            if (task != null) return task;
        }
        return null;
    }

    /** A task is atomic; the deadline is checked before starting each subsequent task. */
    boolean drainUntil(final long deadline, final BooleanSupplier running,
                       final Consumer<ChunkTasks> processor) {
        while (running.getAsBoolean()) {
            if (this.clock.getAsLong() - deadline >= 0L) return this.queue.hasWork();
            final ChunkTasks task = this.nextTask();
            if (task == null) return false;
            try {
                processor.accept(task);
            } finally {
                this.queue.completeTask(task);
            }
        }
        return false;
    }
}
