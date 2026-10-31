package com.sumirelabs.pulsar.dynamic;

import java.util.ArrayDeque;

/** Immutable, transient four-channel light volume. No chunk storage or world access. */
public final class DynamicLightField {
    public final int x, y, z, radius, side;
    private final short[] cells;

    public DynamicLightField(int x, int y, int z, int radius, short[] cells) {
        if (radius < 0 || radius > 14 || cells.length != cube(radius))
            throw new IllegalArgumentException("Invalid dynamic light volume");
        this.x = x; this.y = y; this.z = z; this.radius = radius;
        this.side = radius * 2 + 1; this.cells = cells.clone();
    }
    public static int cube(int radius) { int side = radius * 2 + 1; return side * side * side; }
    public int size() { return cells.length; }
    public boolean sameLight(DynamicLightField other) {
        return other != null && x == other.x && y == other.y && z == other.z && radius == other.radius
                && java.util.Arrays.equals(cells, other.cells);
    }
    public int cell(int index) { return cells[index] & 65535; }
    public int get(int wx, int wy, int wz) {
        int dx = wx - x + radius, dy = wy - y + radius, dz = wz - z + radius;
        return dx < 0 || dy < 0 || dz < 0 || dx >= side || dy >= side || dz >= side
                ? 0 : cell(dx + side * (dz + side * dy));
    }
    /** Low 12 bits are RGB; the fourth nibble is the independently propagated vanilla channel. */
    public static int merge(int a, int b) {
        int result = 0;
        for (int shift = 0; shift < 16; shift += 4)
            result |= Math.max((a >>> shift) & 15, (b >>> shift) & 15) << shift;
        return result;
    }
    public static int peak(int rgb) { return Math.max(rgb & 15, Math.max(rgb >>> 4 & 15, rgb >>> 8 & 15)); }
    public static int brightness(int cell, boolean projected) { return projected ? peak(cell) : cell >>> 12 & 15; }

    /** Directions: east, west, south, north, up, down. Snapshot callbacks happen before this call. */
    public static DynamicLightField propagate(int x, int y, int z, int radius, int emission,
                                              short[] absorption, byte[] exits) {
        int side = radius * 2 + 1, size = cube(radius);
        if (absorption.length != size * 6 || exits.length != size) throw new IllegalArgumentException("Invalid geometry");
        short[] light = new short[size];
        boolean[] queued = new boolean[size];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        int center = radius + side * (radius + side * radius);
        light[center] = (short) emission; queue.add(center); queued[center] = true;
        int[] offsets = {1, -1, side, -side, side * side, -side * side};
        while (!queue.isEmpty()) {
            int at = queue.removeFirst(); queued[at] = false;
            int dx = at % side, dz = at / side % side, dy = at / (side * side);
            for (int dir = 0; dir < 6; dir++) {
                if ((exits[at] & (1 << dir)) != 0
                        || dir == 0 && dx == side - 1 || dir == 1 && dx == 0
                        || dir == 2 && dz == side - 1 || dir == 3 && dz == 0
                        || dir == 4 && dy == side - 1 || dir == 5 && dy == 0) continue;
                int to = at + offsets[dir], cost = absorption[to * 6 + (dir ^ 1)] & 65535;
                int candidate = 0;
                for (int shift = 0; shift < 16; shift += 4)
                    candidate |= Math.max(0, (light[at] >>> shift & 15) - Math.max(1, cost >>> shift & 15)) << shift;
                int next = merge(light[to] & 65535, candidate);
                if (next == (light[to] & 65535)) continue;
                light[to] = (short) next;
                if (!queued[to]) { queue.addLast(to); queued[to] = true; }
            }
        }
        return new DynamicLightField(x, y, z, radius, light);
    }
}
