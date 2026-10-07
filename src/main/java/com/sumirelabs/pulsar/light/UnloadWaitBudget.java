package com.sumirelabs.pulsar.light;

import java.util.function.LongSupplier;

/** Per-world-tick allowance based only on time actually spent waiting for light work. */
final class UnloadWaitBudget {

    static final long DEFAULT_BUDGET_NS = 10_000_000L;

    private final long budgetNs;
    private final LongSupplier nanoClock;
    private long remainingNs;

    UnloadWaitBudget(final long budgetNs, final LongSupplier nanoClock) {
        if (budgetNs < 0L) {
            throw new IllegalArgumentException("Unload wait budget must not be negative");
        }
        this.budgetNs = budgetNs;
        this.nanoClock = nanoClock;
    }

    synchronized void beginTick() {
        this.remainingNs = this.budgetNs;
    }

    synchronized long remainingNs() {
        return this.remainingNs;
    }

    /** Records only the elapsed interval surrounding one Future.get call. */
    synchronized long recordWaitSince(final long startedAtNs) {
        final long elapsedNs = Math.max(0L, this.nanoClock.getAsLong() - startedAtNs);
        this.remainingNs = Math.max(0L, this.remainingNs - elapsedNs);
        return elapsedNs;
    }
}
