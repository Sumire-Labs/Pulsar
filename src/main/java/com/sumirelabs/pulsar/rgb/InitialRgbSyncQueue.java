/* SPDX-License-Identifier: LGPL-3.0-only */
package com.sumirelabs.pulsar.rgb;

import java.util.ArrayDeque;
import java.util.function.BiPredicate;
import java.util.function.LongSupplier;

import it.unimi.dsi.fastutil.ints.IntArrayList;

/** Server-thread cursors over the view at handshake. New watches use the normal chunk-packet path. */
final class InitialRgbSyncQueue<P, W> {

    interface Visit<P, W> {

        /** True after delivery or when this coordinate is no longer watched. */
        boolean send(P player, W world, int x, int z);

    }

    private static final class Cursor<P, W> {

        final P player;
        final W world;
        final int cx, cz, radius;
        int index;
        boolean replay;
        IntArrayList retry;
        IntArrayList failed;

        Cursor(P player, W world, int cx, int cz, int radius) {
            this.player = player;
            this.world = world;
            this.cx = cx;
            this.cz = cz;
            this.radius = radius;
        }

    }

    private final ArrayDeque<Cursor<P, W>> pending = new ArrayDeque<>();
    private final ArrayDeque<Cursor<P, W>> nextTick = new ArrayDeque<>();

    void request(P player, W world, int cx, int cz, int radius) {
        for (var c : pending)
            if (c.player == player && c.world == world && c.cx == cx && c.cz == cz && c.radius == radius
                && c.retry == null) {
                // The client may have discarded colors sent before this new request.
                c.replay |= c.index != 0;
                return;
            }
        removePlayer(player);
        pending.addLast(new Cursor<>(player, world, cx, cz, radius));
    }

    void removePlayer(P player) {
        pending.removeIf(c -> c.player == player);
        nextTick.removeIf(c -> c.player == player);
    }

    void removeWorld(W world) {
        pending.removeIf(c -> c.world == world);
        nextTick.removeIf(c -> c.world == world);
    }

    void drain(int limit, long budgetNs, LongSupplier clock, BiPredicate<P, W> current, Visit<P, W> visit) {
        while (!nextTick.isEmpty())
            pending.addLast(nextTick.removeFirst());
        if (pending.isEmpty())
            return;
        long start = clock.getAsLong();
        for (int i = 0; i < limit && (i == 0 || clock.getAsLong() - start < budgetNs); i++) {
            var c = pending.pollFirst();
            if (c == null)
                break;
            if (!current.test(c.player, c.world))
                continue;
            int width = 2 * c.radius + 1;
            int index = c.retry == null ? c.index : c.retry.getInt(c.index);
            boolean delivered;
            try {
                delivered = visit.send(c.player, c.world, c.cx - c.radius + index / width,
                    c.cz - c.radius + index % width);
            } catch (RuntimeException failure) {
                nextTick.addLast(c);
                throw failure;
            }
            if (!delivered) {
                if (c.failed == null)
                    c.failed = new IntArrayList();
                c.failed.add(index);
            }
            if (++c.index < (c.retry == null ? width * width : c.retry.size())) {
                pending.addLast(c);
            } else if (c.replay || c.failed != null) {
                c.retry = c.replay ? null : c.failed;
                c.failed = null;
                c.index = 0;
                c.replay = false;
                nextTick.addLast(c);
            }
        }
    }

}
