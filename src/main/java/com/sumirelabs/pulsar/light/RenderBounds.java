package com.sumirelabs.pulsar.light;

/**
 * Packed per-section changed-block bounds for render marking. Six
 * section-local nibbles:
 * {@code minX | minY << 4 | minZ << 8 | maxX << 12 | maxY << 16 | maxZ << 20}.
 *
 * <p>Marking only the actually-changed range matters because vanilla's
 * {@code markBlockRangeForRenderUpdate} already inflates the range by one
 * block in each direction — a full-section mark therefore rebuilds all 26
 * neighbouring RenderChunks, while a tight interior range rebuilds one.
 */
public final class RenderBounds {

    private RenderBounds() {}

    public static long pack(final int minX, final int minY, final int minZ,
                            final int maxX, final int maxY, final int maxZ) {
        return (long) (minX | (minY << 4) | (minZ << 8) | (maxX << 12) | (maxY << 16) | (maxZ << 20));
    }

    public static long union(final long a, final long b) {
        return pack(
                Math.min(minX(a), minX(b)), Math.min(minY(a), minY(b)), Math.min(minZ(a), minZ(b)),
                Math.max(maxX(a), maxX(b)), Math.max(maxY(a), maxY(b)), Math.max(maxZ(a), maxZ(b)));
    }

    public static int minX(final long bounds) {
        return (int) bounds & 15;
    }

    public static int minY(final long bounds) {
        return (int) (bounds >>> 4) & 15;
    }

    public static int minZ(final long bounds) {
        return (int) (bounds >>> 8) & 15;
    }

    public static int maxX(final long bounds) {
        return (int) (bounds >>> 12) & 15;
    }

    public static int maxY(final long bounds) {
        return (int) (bounds >>> 16) & 15;
    }

    public static int maxZ(final long bounds) {
        return (int) (bounds >>> 20) & 15;
    }
}
