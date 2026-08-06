package com.sumirelabs.pulsar.light;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkPacketLightTest {

    private static final int LIGHT_ARRAY_BYTES = 2048;

    @Test
    void omitsBlockEmptySectionWithDefaultLight() {
        final byte[] blockLight = new byte[LIGHT_ARRAY_BYTES];
        final byte[] skyLight = new byte[LIGHT_ARRAY_BYTES];
        Arrays.fill(skyLight, (byte) 0xFF);

        assertTrue(PacketLightSection.isTrivial(true, blockLight, skyLight, true));
    }

    @Test
    void keepsBlockLightInBlockEmptySection() {
        final byte[] blockLight = new byte[LIGHT_ARRAY_BYTES];
        blockLight[123] = 0x07;

        assertFalse(PacketLightSection.isTrivial(true, blockLight, null, false));
    }

    @Test
    void keepsNonDefaultSkyLightInBlockEmptySection() {
        final byte[] blockLight = new byte[LIGHT_ARRAY_BYTES];
        final byte[] skyLight = new byte[LIGHT_ARRAY_BYTES];
        Arrays.fill(skyLight, (byte) 0xFF);
        skyLight[123] = (byte) 0xFE;

        assertFalse(PacketLightSection.isTrivial(true, blockLight, skyLight, true));
    }

    @Test
    void ignoresSkyArrayWhenPacketDoesNotWriteSkylight() {
        final byte[] blockLight = new byte[LIGHT_ARRAY_BYTES];
        final byte[] skyLight = new byte[LIGHT_ARRAY_BYTES];

        assertTrue(PacketLightSection.isTrivial(true, blockLight, skyLight, false));
    }

    @Test
    void keepsAnySectionThatContainsBlocks() {
        final byte[] blockLight = new byte[LIGHT_ARRAY_BYTES];
        final byte[] skyLight = new byte[LIGHT_ARRAY_BYTES];
        Arrays.fill(skyLight, (byte) 0xFF);

        assertFalse(PacketLightSection.isTrivial(false, blockLight, skyLight, true));
    }
}
