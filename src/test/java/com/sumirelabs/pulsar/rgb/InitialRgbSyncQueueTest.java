/* SPDX-License-Identifier: LGPL-3.0-only */
package com.sumirelabs.pulsar.rgb;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InitialRgbSyncQueueTest {

    @Test
    void playersAcrossWorldsShareBothBudgetsAndCoverTheirViews() {
        var queue = new InitialRgbSyncQueue<Integer, Integer>();
        queue.request(1, 1, -10, -8, 1);
        queue.request(2, 2, -10, -8, 1);
        var visits = new ArrayList<Integer>();
        var positions = new HashSet<List<Integer>>();
        var clock = new AtomicLong();
        InitialRgbSyncQueue.Visit<Integer, Integer> send = (p, w, x, z) -> {
            assertEquals(p, w);
            assertTrue(x >= -11 && x <= -9 && z >= -9 && z <= -7);
            assertTrue(positions.add(List.of(p, x, z)));
            visits.add(p);
            clock.addAndGet(2);
            return true;
        };
        queue.drain(32, 3, clock::get, (p, w) -> true, send);
        assertEquals(List.of(1, 2), visits);
        queue.drain(3, 100, clock::get, (p, w) -> true, send);
        assertEquals(List.of(1, 2, 1, 2, 1), visits);
        queue.drain(32, 100, clock::get, (p, w) -> true, send);
        assertEquals(18, positions.size());
    }

    @Test
    void retryOnlyUnreadyCoordinatesOnLaterTicksAndCancelRetiredRequests() {
        var queue = new InitialRgbSyncQueue<String, String>();
        queue.request("a", "old", 0, 0, 1);
        var xs = new ArrayList<Integer>();
        queue.drain(32, 100, () -> 0, (p, w) -> true, (p, w, x, z) -> {
            xs.add(x);
            return x != 0 || z != 0;
        });
        assertEquals(9, xs.size());
        queue.drain(32, 100, () -> 0, (p, w) -> true, (p, w, x, z) -> {
            assertEquals(0, x);
            assertEquals(0, z);
            xs.add(x);
            return false;
        });
        assertEquals(10, xs.size());
        queue.removeWorld("old");
        queue.request("b", "new", 1, 1, 2);
        queue.request("c", "new", 1, 1, 2);
        queue.removePlayer("b");
        queue.drain(32, 100, () -> 0, (p, w) -> false, (p, w, x, z) -> fail("Retired request sent"));
        queue.drain(32, 100, () -> 0, (p, w) -> true, (p, w, x, z) -> fail("Retired request retained"));
    }

    @Test
    void failedSendRetainsTheCursorAndUnreadyChunksCanFinish() {
        var queue = new InitialRgbSyncQueue<String, String>();
        queue.request("p", "w", 5, -3, 0);
        assertThrows(IllegalStateException.class, () -> queue.drain(32, 100, () -> 0, (p, w) -> true,
            (p, w, x, z) -> {
                throw new IllegalStateException("send failed");
            }));
        var visits = new ArrayList<List<Integer>>();
        for (int i = 0; i < 3; i++)
            queue.drain(32, 100, () -> 0, (p, w) -> true, (p, w, x, z) -> {
                visits.add(List.of(x, z));
                return visits.size() == 2;
            });
        assertEquals(List.of(List.of(5, -3), List.of(5, -3)), visits);
    }

    @Test
    void repeatedHelloDoesNotRestartActiveScanAndMovedPlayerGetsNewView() {
        var queue = new InitialRgbSyncQueue<String, String>();
        var coords = new ArrayList<Integer>();
        InitialRgbSyncQueue.Visit<String, String> send = (p, w, x, z) -> coords.add(x * 10 + z);
        queue.request("p", "world", 0, 0, 1);
        queue.drain(1, 100, () -> 0, (p, w) -> true, send);
        queue.request("p", "world", 0, 0, 1);
        queue.request("p", "world", 0, 0, 1);
        queue.drain(32, 100, () -> 0, (p, w) -> true, send);
        assertEquals(9, new HashSet<>(coords).size());
        assertEquals(9, coords.size());
        queue.drain(32, 100, () -> 0, (p, w) -> true, send);
        assertEquals(coords.subList(0, 9), coords.subList(9, 18));
        queue.request("p", "world", 0, 0, 1);
        queue.request("p", "world", 3, 4, 0);
        queue.drain(32, 100, () -> 0, (p, w) -> true, send);
        assertEquals(List.of(34), coords.subList(18, coords.size()));
    }

}
