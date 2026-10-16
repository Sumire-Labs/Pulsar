package com.sumirelabs.pulsar.rgb;

import com.sumirelabs.pulsar.core.RgbMixinSelection;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RgbCodecTest {
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
            assertEquals(1, hello.version);
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
