package com.sumirelabs.pulsar.light;

/**
 * Cached per-vertex light for the one-block tiles used by painting renderers.
 *
 * <p>Vanilla samples one combined-light value at the centre of every tile and
 * applies it uniformly to all four vertices. Averaging the cached tile values
 * shared by each corner gives adjacent tiles identical corner values, allowing
 * the GPU to interpolate the lightmap smoothly across the painting (the
 * painting portion of MC-1531).
 */
public final class PaintingLightGrid {

    private final int width;
    private final int height;
    private final int widthPixels;
    private final int heightPixels;
    private final int[] vertexLight;

    private PaintingLightGrid(final int width, final int height, final int[] tileLight) {
        if (width <= 0 || height <= 0 || tileLight.length != width * height) {
            throw new IllegalArgumentException("Invalid painting light-grid dimensions");
        }

        this.width = width;
        this.height = height;
        this.widthPixels = width << 4;
        this.heightPixels = height << 4;
        this.vertexLight = new int[(width + 1) * (height + 1)];

        for (int vertexY = 0; vertexY <= height; vertexY++) {
            for (int vertexX = 0; vertexX <= width; vertexX++) {
                this.vertexLight[vertexX + vertexY * (width + 1)] = averageAdjacentTiles(
                        tileLight, width, height, vertexX, vertexY);
            }
        }
    }

    /**
     * Returns the packed light for a renderer-local painting vertex.
     * Painting renderers place grid vertices at exact 16-pixel intervals.
     */
    public int getLight(final double localX, final double localY) {
        final int vertexX = clamp(
                Math.round((float) (localX + this.widthPixels / 2.0F) / 16.0F),
                0, this.width);
        final int vertexY = clamp(
                Math.round((float) (localY + this.heightPixels / 2.0F) / 16.0F),
                0, this.height);
        return this.vertexLight[vertexX + vertexY * (this.width + 1)];
    }

    public static PaintingLightGrid fromTileLight(final int width, final int height,
                                                  final int... tileLight) {
        return new PaintingLightGrid(width, height, tileLight);
    }

    private static int averageAdjacentTiles(final int[] tileLight, final int width,
                                            final int height, final int vertexX,
                                            final int vertexY) {
        int blockSum = 0;
        int skySum = 0;
        int count = 0;

        final int minTileX = Math.max(vertexX - 1, 0);
        final int maxTileX = Math.min(vertexX, width - 1);
        final int minTileY = Math.max(vertexY - 1, 0);
        final int maxTileY = Math.min(vertexY, height - 1);
        for (int tileY = minTileY; tileY <= maxTileY; tileY++) {
            for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
                final int packed = tileLight[tileX + tileY * width];
                blockSum += packed & 0xFFFF;
                skySum += packed >>> 16 & 0xFFFF;
                count++;
            }
        }

        final int block = blockSum / count & 0xFFFF;
        final int sky = skySum / count & 0xFFFF;
        return block | sky << 16;
    }

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(max, value));
    }
}
