/*
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.sumirelabs.pulsar.light.engine;

/** Test specification: plain integer relaxation, independent of engine queues,
 * LightInfo, packed arithmetic, cached face tables and vanilla light getters. */
final class ReferenceLightSolver {

    enum Kind {
        AIR, STONE, WATER, GLASS, RED_GLASS, BLUE_GLASS, TOP_SLAB, BOTTOM_SLAB, STRONG, WEAK
    }
    static final int WIDTH = 32, HEIGHT = 16, SIZE = WIDTH * WIDTH * HEIGHT;
    private static final int[][] STEPS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    static int index(int x, int y, int z) {
        return x + WIDTH * (z + WIDTH * y);
    }
    static int emission(Kind kind, int channel, boolean scalar, boolean sky) {
        if (kind != Kind.STRONG && kind != Kind.WEAK)
            return 0;
        if (sky)
            return 15; // Explicit, fixed sky-source cells, not a model of sky-column creation.
        if (scalar)
            return kind == Kind.STRONG ? 15 : 6;
        int color = kind == Kind.STRONG ? 0xF24 : 0x161;
        return color >> (8 - channel * 4) & 15;
    }
    static int absorption(Kind kind, int channel, boolean scalar) {
        if (kind == Kind.STONE)
            return 15;
        if (kind == Kind.WATER)
            return 3;
        if (!scalar && (kind == Kind.RED_GLASS && channel != 0 || kind == Kind.BLUE_GLASS && channel != 2))
            return 15;
        return 1;
    }
    private static boolean closed(Kind kind, int dy) {
        return kind == Kind.TOP_SLAB && dy > 0 || kind == Kind.BOTTOM_SLAB && dy < 0;
    }
    private static boolean slab(Kind kind) {
        return kind == Kind.TOP_SLAB || kind == Kind.BOTTOM_SLAB;
    }

    static int[][] solve(Kind[] cells, boolean scalar, boolean sky) {
        int[][] light = new int[scalar ? 1 : 3][SIZE];
        for (int pass = 0; pass < 64; pass++) {
            boolean changed = false;
            for (int y = 0; y < HEIGHT; y++)
                for (int z = 0; z < WIDTH; z++)
                    for (int x = 0; x < WIDTH; x++) {
                        int p = index(x, y, z);
                        Kind dest = cells[p];
                        for (int c = 0; c < light.length; c++) {
                            int level = emission(dest, c, scalar, sky);
                            for (int[] step : STEPS) {
                                int nx = x + step[0], ny = y + step[1], nz = z + step[2];
                                if (nx < 0 || nx >= WIDTH || ny < 0 || ny >= HEIGHT || nz < 0 || nz >= WIDTH)
                                    continue;
                                Kind source = cells[index(nx, ny, nz)];
                                if (closed(dest, step[1]) || closed(source, -step[1]))
                                    continue;
                                if (step[1] == 0 && slab(dest) && slab(source) && dest != source)
                                    continue;
                                level = Math.max(level, light[c][index(nx, ny, nz)] - absorption(dest, c, scalar));
                            }
                            if (level != light[c][p]) {
                                light[c][p] = level;
                                changed = true;
                            }
                        }
                    }
            if (!changed)
                return light;
        }
        throw new AssertionError("Reference relaxation did not converge");
    }

}
