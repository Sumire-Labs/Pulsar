package com.sumirelabs.pulsar.rgb;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class RgbShaderLightDataTest {
    @Test void allEightComponentsKeepTheirOwnBitsIncludingTheSignBit() {
        for (int channel = 0; channel < 8; channel++) for (int level = 0; level < 16; level++) {
            int bits = level << channel * 4;
            assertEquals(bits, RgbShaderLightData.pack(RgbShaderLightData.block(bits), RgbShaderLightData.sky(bits),
                    RgbShaderLightData.blockCanonical(bits), RgbShaderLightData.skyCanonical(bits)));
        }
        assertEquals(-1, RgbShaderLightData.pack(0xFFF, 0xFFF, 15, 15));
    }
    @Test void negativeWorldCoordinatesDoNotAliasInsideTheOwnedWindow() {
        var layout = new RgbShaderLightData.Layout(16, -5, 24);
        var used = new HashSet<Integer>();
        for (int y = -5; y < 19; y++) for (int z = -24; z < -8; z++) for (int x = -35; x < -19; x++)
            assertTrue(used.add(layout.index(x, y, z)));
        assertEquals(layout.entries(), used.size());
        assertEquals(4095, RgbShaderLightData.cell(-1, -1, -1));
        assertEquals(-1, layout.index(0, -6, 0));
        assertFalse(layout.owns(-1, -1, 15, 15));
        assertTrue(layout.owns(-1, -1, -1, -1));
    }
    @Test void stencilMaximumDoesNotLetSkylightOrCanonicalBorrowBetweenColors() {
        var random = new Random(72631);
        for (int i = 0; i < 10000; i++) {
            int a = random.nextInt(), b = random.nextInt(), maximum = RgbShaderLightData.max(a, b);
            for (int shift = 0; shift < 32; shift += 4)
                assertEquals(Math.max(a >>> shift & 15, b >>> shift & 15), maximum >>> shift & 15);
        }
    }
    @Test void shaderViewsKeepSnapshotOwnershipAndRecognizeUniformSections() {
        byte[][][] data = new byte[3][1][];
        data[0][0] = new byte[2048]; java.util.Arrays.fill(data[0][0], (byte)0xFF);
        data[1][0] = new byte[2048]; java.util.Arrays.fill(data[1][0], (byte)0x77);
        var snapshot = new RgbSnapshot(-1, 1, data);
        var section = snapshot.shaderSection(-1, null);
        java.util.Arrays.fill(data[0][0], (byte)0);
        assertEquals(0xFF70, section.constant());
        assertEquals(0xFF70, section.cell(4095));
        assertSame(section, snapshot.shaderSection(-1, section));
        assertEquals(0, snapshot.shaderSection(-2, null).constant());
        int value = RgbShaderLightData.fromCells(section.cell(1), 0xD159);
        assertEquals(0xF70, RgbShaderLightData.block(value));
        assertEquals(0x159, RgbShaderLightData.sky(value));
        assertEquals(15, RgbShaderLightData.blockCanonical(value));
        assertEquals(13, RgbShaderLightData.skyCanonical(value));
    }
}
