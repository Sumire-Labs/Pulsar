package com.sumirelabs.pulsar.dynamic;

import it.unimi.dsi.fastutil.longs.LongHeapPriorityQueue;
import java.util.Arrays;

/** Immutable, transient four-channel light volume. No chunk storage or world access. */
public final class DynamicLightField {
    /** Same nominal range as conventional distance-based dynamic lights; static block lighting is unchanged. */
    public static final double RANGE = 7.75;
    public static final double VISUAL_RANGE = RANGE + 5.0;
    public static final double MAX_RENDER_OFFSET = 3.5;
    public static final int CAPTURE_RADIUS = 13;
    public static final int BLOCKED = 65535;
    private static final int COST_UNIT = 1024;
    public final int x, y, z, radius, side;
    private final short[] cells;
    private final short[] visualLoss;

    public DynamicLightField(int x, int y, int z, int radius, short[] cells) {
        this(x,y,z,radius,cells,null);
    }
    public DynamicLightField(int x, int y, int z, int radius, short[] cells, short[] visualLoss) {
        if (radius < 0 || radius > 14 || cells.length != cube(radius))
            throw new IllegalArgumentException("Invalid dynamic light volume");
        this.x = x; this.y = y; this.z = z; this.radius = radius;
        this.side = radius * 2 + 1; this.cells = cells.clone();
        if (visualLoss != null && visualLoss.length != cells.length * 4) throw new IllegalArgumentException("Invalid visual light volume");
        this.visualLoss = visualLoss == null ? null : visualLoss.clone();
    }
    public static int cube(int radius) { int side = radius * 2 + 1; return side * side * side; }
    public int size() { return cells.length; }
    public boolean sameLight(DynamicLightField other) {
        return other != null && x == other.x && y == other.y && z == other.z && radius == other.radius
                && java.util.Arrays.equals(cells, other.cells) && java.util.Arrays.equals(visualLoss, other.visualLoss);
    }
    public boolean hasVisualData() { return visualLoss != null; }
    public int visualLoss(int cell, int lane) { return visualLoss == null ? BLOCKED : visualLoss[cell * 4 + lane] & 65535; }
    public int emission() { return cell(radius + side * (radius + side * radius)); }
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

    /**
     * Round distance falloff, constrained by actual open-face paths through the snapshot.
     * Effective distance = Euclidean distance + (weighted path length - Manhattan distance).
     * In unobstructed air the second term is zero; detours and opaque media only add loss.
     * RGB shares the same distance multiplier, while additional filter absorption is paid per
     * component. Fully absorbed components cannot propagate, regardless of distance or hue fading.
     * Directions: east, west, south, north, up, down. No callbacks run on this worker.
     */
    public static DynamicLightField propagate(int x, int y, int z, int radius, int emission,
                                              short[] absorption, byte[] exits) {
        if (radius < 0 || radius > 14) throw new IllegalArgumentException("Invalid dynamic light radius");
        int side = radius * 2 + 1, size = cube(radius);
        if (absorption.length != size * 6 || exits.length != size) throw new IllegalArgumentException("Invalid geometry");
        short[] light = new short[size];
        short[] visual = new short[size * 4]; Arrays.fill(visual, (short)BLOCKED);
        double[] distanceCorrection = new double[size];
        for (int i = 0; i < size; i++) {
            int dx = i % side - radius, dz = i / side % side - radius, dy = i / (side * side) - radius;
            distanceCorrection[i] = Math.sqrt(dx * dx + dy * dy + dz * dz) - Math.abs(dx) - Math.abs(dy) - Math.abs(dz);
        }
        int[] costs = new int[size];
        LongHeapPriorityQueue queue = new LongHeapPriorityQueue();
        int center = radius + side * (radius + side * radius);
        int[] offsets = {1, -1, side, -side, side * side, -side * side};
        for (int shift = 0; shift < 16; shift += 4) {
            int strength = emission >>> shift & 15;
            if (strength == 0) continue;
            Arrays.fill(costs, Integer.MAX_VALUE);
            costs[center] = 0; queue.enqueue(center);
            light[center] |= (short) (strength << shift);
            visual[center * 4 + shift / 4] = 0;
            while (!queue.isEmpty()) {
                long entry = queue.dequeueLong();
                int at = (int) entry, cost = (int) (entry >>> 32);
                if (cost != costs[at]) continue;
                int dx = at % side, dz = at / side % side, dy = at / (side * side);
                for (int dir = 0; dir < 6; dir++) {
                    if ((exits[at] & (1 << dir)) != 0
                            || dir == 0 && dx == side - 1 || dir == 1 && dx == 0
                            || dir == 2 && dz == side - 1 || dir == 3 && dz == 0
                            || dir == 4 && dy == side - 1 || dir == 5 && dy == 0) continue;
                    int to = at + offsets[dir], medium = absorption[to * 6 + (dir ^ 1)] & 65535;
                    int scalar = Math.max(1, medium >>> 12 & 15), channel = Math.max(scalar, medium >>> shift & 15);
                    if (channel >= 15) continue;
                    // Round filter losses upward so fixed-point rounding never weakens absorption.
                    int filterLoss = (int) Math.ceil(Math.max(0, channel - scalar) * RANGE * COST_UNIT / strength);
                    int nextCost = cost + scalar * COST_UNIT + filterLoss;
                    if (nextCost >= costs[to]) continue;
                    double distance = nextCost / (double) COST_UNIT + distanceCorrection[to];
                    if (distance > VISUAL_RANGE) continue;
                    int value = (int) (strength * Math.max(0, 1 - distance / RANGE) + 1e-9);
                    costs[to] = nextCost;
                    light[to] = (short) ((light[to] & ~(15 << shift)) | value << shift);
                    int tx = to % side - radius, tz = to / side % side - radius, ty = to / (side * side) - radius;
                    double extra = nextCost / (double)COST_UNIT - Math.abs(tx) - Math.abs(ty) - Math.abs(tz);
                    visual[to * 4 + shift / 4] = (short)Math.min(BLOCKED - 1, (int)Math.ceil(Math.max(0, extra) * 256));
                    queue.enqueue((long) nextCost << 32 | to & 0xffffffffL);
                }
            }
        }
        return new DynamicLightField(x, y, z, radius, light, visual);
    }
}
