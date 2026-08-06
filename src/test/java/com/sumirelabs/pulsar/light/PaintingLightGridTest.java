package com.sumirelabs.pulsar.light;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaintingLightGridTest {

    @Test
    void keepsCornerTileLightAndAveragesSharedVertices() {
        final PaintingLightGrid grid = PaintingLightGrid.fromTileLight(
                2, 2,
                packed(16, 160), packed(32, 144),
                packed(48, 128), packed(64, 112));

        assertEquals(packed(16, 160), grid.getLight(-16, -16));
        assertEquals(packed(24, 152), grid.getLight(0, -16));
        assertEquals(packed(40, 136), grid.getLight(0, 0));
        assertEquals(packed(64, 112), grid.getLight(16, 16));
    }

    @Test
    void clampsRendererCoordinatesToPaintingEdges() {
        final PaintingLightGrid grid = PaintingLightGrid.fromTileLight(
                2, 1, packed(32, 96), packed(64, 160));

        assertEquals(packed(32, 96), grid.getLight(-100, 0));
        assertEquals(packed(64, 160), grid.getLight(100, 0));
    }

    @Test
    void rejectsInvalidTileArrays() {
        assertThrows(IllegalArgumentException.class,
                () -> PaintingLightGrid.fromTileLight(2, 2, packed(0, 0)));
    }

    private static int packed(final int block, final int sky) {
        return block & 0xFFFF | (sky & 0xFFFF) << 16;
    }
}
