package com.sumirelabs.pulsar.light;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UnloadWaitBudgetTest {

    @Test
    void accumulatesOnlyMeasuredWaitAcrossSeveralUnloadsAndResetsAtNextTick() {
        final long[] now = {100L};
        final UnloadWaitBudget budget = new UnloadWaitBudget(10_000_000L, () -> now[0]);
        budget.beginTick();

        final long firstWaitStarted = now[0];
        now[0] += 4_000_000L;
        assertEquals(4_000_000L, budget.recordWaitSince(firstWaitStarted));
        assertEquals(6_000_000L, budget.remainingNs());

        // Simulate unrelated tick work: it does not consume the unload allowance.
        now[0] += 100_000_000L;
        assertEquals(6_000_000L, budget.remainingNs());

        final long secondWaitStarted = now[0];
        now[0] += 6_000_000L;
        assertEquals(6_000_000L, budget.recordWaitSince(secondWaitStarted));
        assertEquals(0L, budget.remainingNs());

        budget.beginTick();
        assertEquals(10_000_000L, budget.remainingNs());
    }

    @Test
    void repeatedUnloadsCannotSpendMoreThanTheTickAllowance() {
        final long[] now = {0L};
        final UnloadWaitBudget budget = new UnloadWaitBudget(10_000_000L, () -> now[0]);
        budget.beginTick();

        int unloadsThatWaited = 0;
        for (int unload = 0; unload < 5; ++unload) {
            final long allowance = budget.remainingNs();
            if (allowance == 0L) {
                break;
            }
            final long waitStarted = now[0];
            now[0] += Math.min(4_000_000L, allowance);
            budget.recordWaitSince(waitStarted);
            unloadsThatWaited++;
        }

        assertEquals(3, unloadsThatWaited);
        assertEquals(0L, budget.remainingNs());
    }
}
