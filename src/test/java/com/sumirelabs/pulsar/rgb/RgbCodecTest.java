package com.sumirelabs.pulsar.rgb;

import com.sumirelabs.pulsar.core.RgbMixinSelection;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RgbCodecTest {
    @Test void whiteSkyRequiresACompleteFilterFreeHaloAndLimitsPublication() {
        int[] reads = {0};
        assertTrue(RgbSkyWhiteRegion.canUseWhite(-10, 20, (x,z) -> { reads[0]++; return false; }));
        assertEquals(25, reads[0]);
        for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) {
            int filterX = -10 + dx, filterZ = 20 + dz;
            assertFalse(RgbSkyWhiteRegion.canUseWhite(-10, 20, (x,z) -> x == filterX && z == filterZ));
        }
        assertTrue(RgbSkyWhiteRegion.canUseWhite(-10, 20, (x,z) -> x == -7));
        assertTrue(RgbSkyWhiteRegion.contains(-10,20,-11,21));
        assertFalse(RgbSkyWhiteRegion.contains(-10,20,-12,20));
        assertFalse(RgbSkyWhiteRegion.contains(Integer.MIN_VALUE,0,Integer.MAX_VALUE,0));
        assertTrue(RgbFilterPaletteChecks.hasFilters(null, null));
        assertTrue(new RgbFilterPalette() {}.pulsar$mayFilterSky());
    }
    @Test void whitePublicationSharesOnlyOwnedCanonicalBytes() throws Exception {
        var height = com.sumirelabs.pulsar.util.WorldHeightContext.VANILLA;
        var canonical = new com.sumirelabs.pulsar.light.SWMRNibbleArray[height.getTotalLightSections()];
        for (int s = 0; s < canonical.length; s++) canonical[s] = new com.sumirelabs.pulsar.light.SWMRNibbleArray();
        canonical[2].set(0, 7); canonical[2].updateVisible();
        RgbSnapshot first = RgbStorage.whiteSkySnapshot(height.getMinLightSection(), 1, canonical, null);
        var field = RgbSnapshot.class.getDeclaredField("data"); field.setAccessible(true);
        var scalar = RgbSnapshot.class.getDeclaredField("canonical"); scalar.setAccessible(true);
        byte[][][] data = (byte[][][])field.get(first);
        assertSame(data[0][2], data[1][2]); assertSame(data[0][2], data[2][2]);
        assertSame(data[0][2], ((byte[][])scalar.get(first))[2]);
        assertNotSame(canonical[2].getVisibleData(), data[0][2]);
        canonical[2].set(0, 3); canonical[2].updateVisible();
        assertEquals(0x777, first.get(0, 16, 0));
        assertEquals(0x333, RgbStorage.whiteSkySnapshot(height.getMinLightSection(),2,canonical,first).get(0,16,0));
        first.section(0,2)[0] = 0;
        assertEquals(0x777, first.get(0,16,0));
    }
    @Test void coloredMaterializationCopiesWhiteCanonicalStateWithoutAliasing() {
        var height = com.sumirelabs.pulsar.util.WorldHeightContext.VANILLA;
        var canonical = new com.sumirelabs.pulsar.light.SWMRNibbleArray[height.getTotalLightSections()];
        for (int s = 0; s < canonical.length; s++) canonical[s] = new com.sumirelabs.pulsar.light.SWMRNibbleArray(null,true);
        canonical[3] = new com.sumirelabs.pulsar.light.SWMRNibbleArray();
        canonical[3].set(0, 7); canonical[3].updateVisible();
        canonical[4] = new com.sumirelabs.pulsar.light.SWMRNibbleArray(); canonical[4].setFull(); canonical[4].updateVisible();
        boolean[] empty = new boolean[16]; empty[0] = true;
        RgbStorage colored = RgbStorage.fromSky(height, canonical, empty);
        empty[0] = false; assertTrue(colored.empty[0][0]);
        colored.empty[0][0] = false; assertTrue(colored.empty[1][0]);
        assertEquals(0x777, colored.skySnapshot(height,1,canonical,null).get(0,16,0));
        colored.channels[0][3].set(0,0); colored.channels[0][3].updateVisible();
        assertEquals(7, canonical[3].getVisible(0));
        assertEquals(7, colored.channels[1][3].getVisible(0));
        assertEquals(0x077, colored.skySnapshot(height,2,canonical,null).get(0,16,0));
        assertTrue(colored.channels[0][4].isFullVisible());
    }
    @Test void optimizedSkyResolutionMatchesExistingExtrusionForRandomSectionStates() {
        var height = com.sumirelabs.pulsar.util.WorldHeightContext.VANILLA;
        var random = new java.util.Random(551391);
        for (int trial = 0; trial < 8; trial++) {
            RgbStorage storage = new RgbStorage(height.getMinLightSection(), height.getTotalLightSections());
            var canonical = new com.sumirelabs.pulsar.light.SWMRNibbleArray[height.getTotalLightSections()];
            for (int c = 0; c < 4; c++) for (int s = 0; s < canonical.length; s++) {
                com.sumirelabs.pulsar.light.SWMRNibbleArray nibble = null;
                switch (random.nextInt(5)) {
                    case 1 -> nibble = new com.sumirelabs.pulsar.light.SWMRNibbleArray(null, true);
                    case 2 -> nibble = new com.sumirelabs.pulsar.light.SWMRNibbleArray();
                    case 3 -> { nibble = new com.sumirelabs.pulsar.light.SWMRNibbleArray(); nibble.setFull(); nibble.updateVisible(); }
                    case 4 -> { byte[] bytes = new byte[2048]; random.nextBytes(bytes); nibble = new com.sumirelabs.pulsar.light.SWMRNibbleArray(bytes); }
                }
                if (c == 3) canonical[s] = nibble; else storage.channels[c][s] = nibble;
            }
            RgbSnapshot snapshot = storage.skySnapshot(height, 1, canonical, null);
            for (int y = height.getMinLightSection() * 16; y < (height.getMaxLightSection() + 1) * 16; y++)
                for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    int rgb = snapshot.get(x, y, z);
                    for (int c = 0; c < 4; c++) {
                        int expected = com.sumirelabs.pulsar.light.ChunkLightHelper.getSkyLight(height, c == 3 ? canonical : storage.channels[c], x, y, z);
                        assertEquals(expected, c == 3 ? snapshot.canonical(x, y, z) : RgbLight.channel(rgb, c));
                    }
                }
        }
    }
    @Test void fullSkyPlanesUseMaskOnlyAndRemainImmutable() {
        byte[] full = new byte[2048]; java.util.Arrays.fill(full, (byte)0xFF);
        byte[][][] rgb = {{full}, {full}, {full}};
        var buffer = Unpooled.buffer();
        try {
            new RgbPacket(0, 0, 0, new RgbSnapshot(0, 1, rgb, new byte[][]{full}), true).toBytes(buffer);
            assertEquals(29, buffer.readableBytes());
            RgbPacket copy = new RgbPacket(); copy.fromBytes(buffer);
            assertEquals(0xFFF, copy.snapshot.get(0, 0, 0));
            copy.snapshot.section(0, 0)[0] = 0;
            assertEquals(0xFFF, copy.snapshot.get(0, 0, 0));
            assertEquals(15, copy.snapshot.canonical(0, 0, 0));
            assertTrue(copy.snapshot.canonicalFull(0));
            byte[] target = new byte[2048]; copy.snapshot.copyCanonicalTo(0, target);
            assertEquals((byte)0xFF, target[2047]); target[0] = 0;
            assertEquals(15, copy.snapshot.canonical(0, 0, 0));
        } finally { buffer.release(); }
    }
    @Test void skyPacketsHaveAnIndependentLaneAndRetainCanonicalLight() {
        byte[][][] data = new byte[3][1][]; data[0][0] = new byte[2048]; data[0][0][0] = 7;
        byte[][] canonical = new byte[1][]; canonical[0] = new byte[2048]; canonical[0][0] = 15;
        var buffer = Unpooled.buffer();
        try {
            new RgbPacket(0, 1, 2, new RgbSnapshot(0, 9, data, canonical), true).toBytes(buffer);
            RgbPacket copy = new RgbPacket(); copy.fromBytes(buffer);
            assertTrue(copy.sky); assertEquals(0x700, copy.snapshot.get(0, 0, 0));
            assertEquals(15, copy.snapshot.canonical(0, 0, 0));
        } finally { buffer.release(); }
    }
    @Test void coloredSkyAndNightUseSeparateChromaticAndCanonicalValues() {
        int daylight = RgbClient.modulateSky(0xFFFFFFFF, 0, 0, 0, 0, 10, 0, 0, 15, 1);
        assertTrue((daylight & 255) > (daylight >> 8 & 255));
        assertTrue((daylight & 255) < 255);
        assertEquals(0xFFFFFFFF, RgbClient.modulateSky(0xFFFFFFFF, 0, 0, 0, 0, 15, 15, 15, 15, 1));
        assertEquals(0xFFFFFFFF, RgbClient.modulateSky(0xFFFFFFFF, 0, 0, 0, 0, 10, 0, 0, 15, 0));
    }
    @Test void skySnapshotResolvesAbsentSectionShadowsAndOwnsWorkerBytes() {
        var height = com.sumirelabs.pulsar.util.WorldHeightContext.VANILLA;
        RgbStorage storage = new RgbStorage(height.getMinLightSection(), height.getTotalLightSections());
        var canonical = new com.sumirelabs.pulsar.light.SWMRNibbleArray[height.getTotalLightSections()];
        for (int i = 0; i < canonical.length; i++) canonical[i] = new com.sumirelabs.pulsar.light.SWMRNibbleArray(null, true);
        for (int c = 0; c < 3; c++) {
            storage.channels[c][3] = new com.sumirelabs.pulsar.light.SWMRNibbleArray();
            storage.channels[c][3].set(0, 7 - c); storage.channels[c][3].updateVisible();
        }
        canonical[3] = new com.sumirelabs.pulsar.light.SWMRNibbleArray(); canonical[3].set(0, 15); canonical[3].updateVisible();
        RgbSnapshot first = storage.skySnapshot(height, 1, canonical, null);
        int lowerY = (height.getMinLightSection() + 2) * 16;
        assertEquals(0x765, first.get(0, lowerY, 0));
        assertEquals(0x765, first.get(0, lowerY + 15, 0));
        assertEquals(15, first.canonical(0, lowerY, 0));
        assertEquals(0, first.get(1, lowerY, 0));
        storage.channels[0][3].set(0, 0); storage.channels[0][3].updateVisible();
        assertEquals(0x765, first.get(0, lowerY, 0));
        assertEquals(0x065, storage.skySnapshot(height, 2, canonical, first).get(0, lowerY, 0));
    }
    @Test void unchangedSectionsShareImmutablePayloadWithoutSharingWorkerBytes() throws Exception {
        byte[][][] live = new byte[3][2][];
        live[0][0] = new byte[2048]; live[0][0][0] = 15;
        live[1][1] = new byte[2048]; live[1][1][0] = 7;
        byte[][] canonical = new byte[2][]; canonical[0] = live[0][0];
        RgbSnapshot first = RgbSnapshot.capture(-1, 1, live, canonical, null);
        live[0][0][0] = 14;
        RgbSnapshot next = RgbSnapshot.capture(-1, 2, live, canonical, first);
        var field = RgbSnapshot.class.getDeclaredField("data"); field.setAccessible(true);
        byte[][][] a = (byte[][][])field.get(first), b = (byte[][][])field.get(next);
        assertSame(a[1][1], b[1][1]);
        assertNotSame(a[0][0], b[0][0]); assertNotSame(live[0][0], b[0][0]);
        live[0][0][0] = 0; live[1][1][0] = 0;
        assertEquals(0xF00, first.get(0, -16, 0));
        assertEquals(0xE00, next.get(0, -16, 0));
        assertEquals(14, next.canonical(0, -16, 0));
        assertEquals(0x070, next.get(0, 0, 0));
        live[1][1] = null;
        RgbSnapshot cleared = RgbSnapshot.capture(-1, 3, live, canonical, next);
        assertEquals(0, cleared.get(0, 0, 0));
    }
    @Test void snapshotReuseRejectsDifferentHeightLayouts() {
        byte[][][] live = new byte[3][1][];
        live[0][0] = new byte[2048]; live[0][0][0] = 15;
        RgbSnapshot old = RgbSnapshot.capture(0, 1, live, new byte[1][], null);
        RgbSnapshot next = RgbSnapshot.capture(-1, 2, live, new byte[1][], old);
        assertEquals(0xF00, next.get(0, -16, 0));
        assertEquals(0, next.get(0, 0, 0));
    }
    @Test void filteredLightIsNotRenormalizedToUnfilteredBrightness() {
        int filtered = RgbClient.modulate(0xFFFFFFFF, 5, 0, 0, 0, 15);
        int original = RgbClient.modulate(0xFFFFFFFF, 15, 0, 0, 0, 15);
        assertTrue((filtered & 255) < (original & 255));
        assertEquals(0xFF, filtered >>> 24);
        assertEquals(0xFF123456, RgbClient.modulate(0xFF123456, 15, 15, 15, 0, 15));
    }
    @Test void canonicalLightIsIndependentAndPartOfPublicationEquality() {
        byte[][][] rgb = new byte[3][1][];
        byte[][] scalar = new byte[1][]; scalar[0] = new byte[2048]; scalar[0][0] = 7;
        RgbSnapshot first = new RgbSnapshot(0, 1, rgb, scalar);
        scalar[0][0] = 9;
        assertEquals(7, first.canonicalSection(0)[0]);
        assertFalse(first.sameLightData(new RgbSnapshot(0, 2, rgb, scalar)));
        assertTrue(first.sameLightData(new RgbSnapshot(0, 3, rgb, new byte[][]{first.canonicalSection(0)})));
    }
    @Test void emptyPacketExplicitlyClearsColorAndCanonicalLight() {
        RgbPacket packet = new RgbPacket(0, 0, 0, new RgbSnapshot(0, 2, new byte[3][1][]));
        var bytes = Unpooled.buffer();
        try {
            packet.toBytes(bytes); assertEquals(29, bytes.readableBytes());
            RgbPacket copy = new RgbPacket(); copy.fromBytes(bytes);
            assertEquals(0, copy.snapshot.get(0, 0, 0));
            assertNull(copy.snapshot.canonicalSection(0));
        } finally { bytes.release(); }
    }
    @Test void helloIsBoundedAndCarriesItsProtocolVersion() {
        var bytes = Unpooled.buffer();
        try {
            new RgbHello().toBytes(bytes);
            RgbHello hello = new RgbHello(); hello.fromBytes(bytes);
            assertEquals(RgbHello.VERSION, hello.version);
            bytes.clear(); bytes.writeLong(1);
            assertThrows(IllegalArgumentException.class, () -> hello.fromBytes(bytes));
        } finally { bytes.release(); }
    }
    @Test void publishedStorageDoesNotChangeWhenWorkersWriteAgain() {
        RgbStorage storage = new RgbStorage(-1, 2);
        storage.channels[0][0] = new com.sumirelabs.pulsar.light.SWMRNibbleArray();
        storage.channels[0][0].set(0, 15); storage.channels[0][0].updateVisible();
        RgbSnapshot first = storage.snapshot(1);
        storage.channels[0][0].set(0, 0); storage.channels[0][0].updateVisible();
        assertEquals(0xF00, first.get(0, -16, 0));
        assertEquals(0, storage.snapshot(2).get(0, -16, 0));
    }
    @Test void whitePreservesVertexColorAndSkylightDesaturatesColor() {
        assertEquals(0xFF123456, RgbClient.modulate(0xFF123456, 15, 15, 15, 0));
        int red = RgbClient.modulate(0xFFFFFFFF, 15, 0, 0, 0);
        int daylight = RgbClient.modulate(0xFFFFFFFF, 15, 0, 0, 15);
        assertEquals(255, red & 255);
        assertTrue((red >> 8 & 255) < (daylight >> 8 & 255));
        assertEquals(255, red >>> 24);
    }
    @Test void componentsAndScaling() {
        for (int c = 0; c < 4096; c++) assertEquals(c, RgbLight.scale(c, 15));
        assertEquals(0, RgbLight.scale(0xFFF, 0));
        assertEquals(0x700, RgbLight.scale(0xF00, 7));
        assertThrows(IllegalArgumentException.class, () -> RgbLight.pack(16, 0, 0));
        assertEquals(0xF40, RgbLight.normalize(0x720));
        assertThrows(IllegalArgumentException.class, () -> RgbLight.normalize(0));
    }
    @Test void snapshotOwnsItsDataAndHandlesNegativeHeight() {
        byte[][][] data = new byte[3][2][];
        data[0][0] = new byte[2048]; data[0][0][0] = (byte) 0xAF;
        RgbSnapshot snapshot = new RgbSnapshot(-1, 1, data);
        data[0][0][0] = 0;
        assertEquals(0xF00, snapshot.get(0, -16, 0));
        assertEquals(0xA00, snapshot.get(1, -16, 0));
        assertEquals(0, snapshot.get(0, -32, 0));
        snapshot.section(0, 0)[0] = 0;
        assertEquals(0xF00, snapshot.get(0, -16, 0));
    }
    @Test void sparsePacketRoundTripIncludesExplicitEmptySections() {
        byte[][][] data = new byte[3][3][];
        data[2][1] = new byte[2048]; data[2][1][2047] = (byte) 0xB0;
        RgbPacket a = new RgbPacket(-1, -44, 16, new RgbSnapshot(-1, 98, data));
        var buffer = Unpooled.buffer();
        try {
            a.toBytes(buffer);
            assertEquals(28 + 3 + 4096, buffer.readableBytes());
            RgbPacket b = new RgbPacket(); b.fromBytes(buffer);
            assertEquals(a.dimension, b.dimension); assertEquals(a.x, b.x); assertEquals(a.z, b.z);
            assertEquals(98, b.snapshot.revision);
            assertEquals(0xB, b.snapshot.get(15, 15, 15));
            assertEquals(0, b.snapshot.get(0, -16, 0));
        } finally { buffer.release(); }
    }
    @Test void packetRejectsHugeAndMalformedPayloads() {
        var buffer = Unpooled.buffer();
        try {
            buffer.writeInt(0).writeInt(0).writeInt(0).writeInt(0).writeLong(1).writeInt(Integer.MAX_VALUE);
            assertThrows(IllegalArgumentException.class, () -> new RgbPacket().fromBytes(buffer));
            buffer.clear(); buffer.writeInt(0).writeInt(0).writeInt(0).writeInt(0).writeLong(1).writeInt(1).writeByte(16);
            assertThrows(IllegalArgumentException.class, () -> new RgbPacket().fromBytes(buffer));
        } finally { buffer.release(); }
    }
    @Test void scalarHasNoRgbMixinHooksAndOldConfigsCannotEnableThem() {
        assertFalse(RgbMixinSelection.selectsRgb("I:configVersion=2\nS:lightingMode=SCALAR\n"));
        assertFalse(RgbMixinSelection.selectsRgb("I:configVersion=1\nS:lightingMode=RGB\n"));
        assertTrue(RgbMixinSelection.selectsRgb("  I:configVersion=2\n  S:lightingMode=RGB\n"));
        assertFalse(RgbMixinSelection.selectsRgb("# S:lightingMode=RGB\nI:configVersion=2\n"));
    }
}
