package com.sumirelabs.pulsar.light;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ClientRenderUpdatesTest {
    @Test
    void oneExpensiveMarkCanOvershootButDoesNotStartAnotherMark() {
        var updates = new ClientRenderUpdates<Object>();
        Object chunk = new Object();
        for (int section = 0; section < 20; section++) updates.add(0, chunk, section, 0L);
        long[] clock = {0};
        assertEquals(1, updates.drainWhile(key -> chunk, (key, section, bounds) -> clock[0] += 5,
                () -> clock[0] < 2));
        assertEquals(5, clock[0]);
        assertEquals(19, updates.drain(key -> chunk, (key, section, bounds) -> {}));
    }

    @Test
    void callbackClearDropsCarriedMarksWithoutDroppingSubsequentAdditions() {
        var updates = new ClientRenderUpdates<Object>();
        Object chunk = new Object();
        for (int section = 0; section < 20; section++) updates.add(0, chunk, section, 0L);
        assertEquals(1, updates.drain(key -> chunk, (key, section, bounds) -> {
            updates.clear();
            updates.add(0, chunk, 99, 0L);
        }));
        assertEquals(1, updates.drain(key -> chunk, (key, section, bounds) -> assertEquals(99, section)));
        assertEquals(0, updates.drain(key -> chunk, (key, section, bounds) -> fail("Stale range")));
    }

    @Test
    void budgetYieldsRetainEveryRangeAndDoNotRepeatAlreadySentMarks() {
        var updates = new ClientRenderUpdates<Object>();
        Object chunk = new Object();
        for (int section = 0; section < 30; section++) updates.add(0L, chunk, section, 0L);
        var seen = new java.util.HashSet<Integer>();
        for (int tick = 0; tick < 10; tick++) {
            int[] count = {0};
            assertEquals(3, updates.drainWhile(key -> chunk, (key, section, bounds) -> {
                assertTrue(seen.add(section));
                count[0]++;
            }, () -> count[0] < 3));
        }
        assertEquals(30, seen.size());
        assertEquals(0, updates.drain(key -> chunk, (a, b, c) -> fail("Already sent")));
    }

    @Test
    void expiredBudgetDoesNotConsumeMarksAndUnloadingDropsCarriedMarks() {
        var updates = new ClientRenderUpdates<Object>();
        Object chunk = new Object();
        updates.add(0L, chunk, 0, RenderBounds.pack(0, 4, 15, 0, 4, 15));
        updates.add(0L, chunk, 0, RenderBounds.pack(15, 4, 0, 15, 4, 0));
        assertEquals(0, updates.drainWhile(key -> chunk, (a, b, c) -> fail("Expired"), () -> false));
        int[] marks = {0};
        assertEquals(1, updates.drainWhile(key -> chunk, (a, b, c) -> marks[0]++, () -> marks[0] == 0));
        updates.remove(0L);
        assertEquals(0, updates.drain(key -> chunk, (a, b, c) -> fail("Unloaded carry")));
    }

    @Test
    void replacementAndReentrantAdditionsSurviveABudgetYield() {
        var updates = new ClientRenderUpdates<Object>();
        Object old = new Object(), replacement = new Object();
        updates.add(0L, old, 0, RenderBounds.pack(0, 4, 15, 0, 4, 15));
        updates.add(0L, old, 0, RenderBounds.pack(15, 4, 0, 15, 4, 0));
        int[] count = {0};
        assertEquals(1, updates.drainWhile(key -> old, (a, b, c) -> {
            count[0]++;
            updates.add(0L, replacement, 2, 0L);
        }, () -> count[0] == 0));
        assertEquals(0, updates.drain(key -> replacement, (a, b, c) -> fail("Stale carry")));
        assertEquals(1, updates.drain(key -> replacement, (a, b, c) -> assertEquals(2, b)));
    }

    @Test
    void mergesRepeatedSkyAndBlockMarksWithoutExpandingRendererCoverage() {
        final ClientRenderUpdates<Object> updates = new ClientRenderUpdates<>();
        final Object chunk = new Object();
        for (int i = 0; i < 500; i++) {
            updates.add(-1L, chunk, -2, RenderBounds.pack(2, 3, 4, 2, 3, 4));
            updates.add(-1L, chunk, -2, RenderBounds.pack(12, 10, 9, 12, 10, 9));
        }
        final List<Long> marks = new ArrayList<>();
        assertEquals(1, updates.drain(key -> chunk, (key, section, bounds) -> {
            assertEquals(-1L, key);
            assertEquals(-2, section);
            marks.add(bounds);
        }));
        assertEquals(RenderBounds.pack(2, 3, 4, 12, 10, 9), marks.get(0));
        assertEquals(0, updates.drain(key -> chunk, (key, section, bounds) -> fail("Already drained")));
    }

    @Test
    void diagonalBoundaryMarksDoNotDirtyAdditionalNeighborSections() {
        final ClientRenderUpdates<Object> updates = new ClientRenderUpdates<>();
        final Object chunk = new Object();
        final long a = RenderBounds.pack(0, 4, 15, 0, 4, 15);
        final long b = RenderBounds.pack(15, 4, 0, 15, 4, 0);
        updates.add(0L, chunk, 0, a);
        updates.add(0L, chunk, 0, b);
        final int[] coverage = {0};
        assertEquals(2, updates.drain(key -> chunk, (key, section, bounds) ->
                coverage[0] |= ClientRenderUpdates.sectionMask(bounds)));
        assertEquals(ClientRenderUpdates.sectionMask(a) | ClientRenderUpdates.sectionMask(b), coverage[0]);
        assertNotEquals(coverage[0], ClientRenderUpdates.sectionMask(RenderBounds.union(a, b)));
    }

    @Test
    void unloadedOrReplacedChunksCannotInheritOldMarks() {
        final ClientRenderUpdates<Object> updates = new ClientRenderUpdates<>();
        final Object oldChunk = new Object(), replacement = new Object();
        updates.add(4L, oldChunk, 1, 0L);
        assertEquals(0, updates.drain(key -> replacement, (key, section, bounds) -> fail("Stale chunk")));
        updates.add(4L, oldChunk, 1, RenderBounds.pack(0, 0, 0, 15, 15, 15));
        updates.add(4L, replacement, 1, 0L);
        final List<Long> marks = new ArrayList<>();
        assertEquals(1, updates.drain(key -> replacement, (key, section, bounds) -> marks.add(bounds)));
        assertEquals(List.of(0L), marks);
        updates.add(4L, replacement, 1, 0L);
        updates.remove(4L);
        assertEquals(0, updates.drain(key -> replacement, (key, section, bounds) -> fail("Unloaded")));
    }

    @Test
    void callbackTriggeredMarksWaitForTheNextDrain() {
        final ClientRenderUpdates<Object> updates = new ClientRenderUpdates<>();
        final Object chunk = new Object();
        updates.add(0L, chunk, 0, 0L);
        assertEquals(1, updates.drain(key -> chunk, (key, section, bounds) -> {
            updates.add(0L, chunk, 1, 0L);
            assertEquals(0, updates.drain(ignored -> chunk, (a, b, c) -> fail("Nested drain")));
        }));
        assertEquals(1, updates.drain(key -> chunk, (key, section, bounds) -> assertEquals(1, section)));
    }
}
