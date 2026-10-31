package com.sumirelabs.pulsar.dynamic;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class DynamicLightFieldTest {
    private static int index(int r, int x, int y, int z) { int s = r * 2 + 1; return x + r + s * (z + r + s * (y + r)); }
    private static short[] air(int r) { short[] costs = new short[DynamicLightField.cube(r) * 6]; Arrays.fill(costs, (short)0x1111); return costs; }
    @Test void bothHandsCombinePerChannelAndLightDecaysInAllDirections() {
        int emission = DynamicLightField.merge(0xEE40, 0x9009);
        assertEquals(0xEE49, emission);
        var field = DynamicLightField.propagate(-32, 64, 17, 13, emission, air(13), new byte[DynamicLightField.cube(13)]);
        assertEquals(0xDD38, field.get(-31,64,17));
        assertEquals(0xBB16, field.get(-33,65,18));
        assertEquals(0, field.get(-32,64,31));
        assertEquals(0x1100, field.get(-45,64,17));
    }
    @Test void opaqueWallCannotLeakThroughOrAroundTheVolumeBoundary() {
        int r = 5; short[] costs = air(r);
        for (int y=-r;y<=r;y++) for(int z=-r;z<=r;z++) Arrays.fill(costs,index(r,1,y,z)*6,index(r,1,y,z)*6+6,(short)0xFFFF);
        var field = DynamicLightField.propagate(0,0,0,r,0x6666,costs,new byte[DynamicLightField.cube(r)]);
        assertEquals(0,field.get(2,0,0)); assertEquals(0x5555,field.get(-1,0,0));
    }
    @Test void coloredFilterNeverRestoresAnAbsorbedChannelButVanillaChannelSurvives() {
        int r=5; short[] costs=air(r);
        for(int y=-r;y<=r;y++) for(int z=-r;z<=r;z++) Arrays.fill(costs,index(r,1,y,z)*6,index(r,1,y,z)*6+6,(short)0x11FF);
        var field=DynamicLightField.propagate(0,0,0,r,0x6666,costs,new byte[DynamicLightField.cube(r)]);
        assertEquals(0x4400,field.get(2,0,0)); assertEquals(0x2200,field.get(4,0,0));
        assertEquals(4,DynamicLightField.brightness(field.get(2,0,0),true));
        assertEquals(4,DynamicLightField.brightness(field.get(2,0,0),false));
    }
    @Test void closedOutgoingFaceBlocksLightEvenWhenTheDestinationIsAir() {
        short[] costs=new short[27*6];Arrays.fill(costs,(short)0xFFFF);
        Arrays.fill(costs,index(1,1,0,0)*6,index(1,1,0,0)*6+6,(short)0x1111);
        byte[] exits=new byte[27];exits[index(1,0,0,0)]=1;
        assertEquals(0,DynamicLightField.propagate(0,0,0,1,0xFFFF,costs,exits).get(1,0,0));
        exits[index(1,0,0,0)]=0;
        assertEquals(0xEEEE,DynamicLightField.propagate(0,0,0,1,0xFFFF,costs,exits).get(1,0,0));
    }
    @Test void fieldsOwnTheirCellsAndOverlapsCanBeRemovedWithoutMutatingStaticLight() {
        short[] cells={(short)0xF007};var blue=new DynamicLightField(1,2,3,0,cells);cells[0]=0;
        var red=new DynamicLightField(1,2,3,0,new short[]{(short)0x8800});
        assertEquals(0xF807,DynamicLightField.merge(blue.get(1,2,3),red.get(1,2,3)));
        assertEquals(0x8800,red.get(1,2,3));assertEquals(0xF007,blue.get(1,2,3));
        assertEquals(15,DynamicLightField.brightness(blue.get(1,2,3),false));
        assertEquals(7,DynamicLightField.brightness(blue.get(1,2,3),true));
    }
    @Test void codecRoundTripsMaximumVolumeAndRejectsTrailingAndTruncatedData() {
        short[] cells=new short[DynamicLightField.cube(14)];var random=new java.util.Random(4);
        for(int i=0;i<cells.length;i++)cells[i]=(short)random.nextInt(65536);
        var packet=new DynamicLightPacket();packet.mode=DynamicLights.Mode.GAMEPLAY;
        packet.session=100;packet.revision=123;packet.dimension=-1;packet.source=7;packet.rgb=true;packet.projected=true;
        packet.field=new DynamicLightField(-8,72,15,14,cells);
        var buffer=Unpooled.buffer();
        try {
            packet.toBytes(buffer);var decoded=new DynamicLightPacket();decoded.fromBytes(buffer.duplicate());
            assertTrue(packet.field.sameLight(decoded.field));assertEquals(100,decoded.session);assertEquals(123,decoded.revision);
            assertEquals(packet.mode,decoded.mode);assertEquals(-1,decoded.dimension);
            assertThrows(IllegalArgumentException.class,()->new DynamicLightPacket().fromBytes(buffer.slice(0,buffer.readableBytes()-1)));
            buffer.writeByte(0);assertThrows(IllegalArgumentException.class,()->new DynamicLightPacket().fromBytes(buffer.duplicate()));
        } finally { buffer.release(); }
    }
    @Test void tombstonesAndOffAcknowledgementRoundTripWithoutAllocatingAField() {
        var packet=new DynamicLightPacket();packet.mode=DynamicLights.Mode.OFF;packet.session=77;
        var buffer=Unpooled.buffer();
        try { packet.toBytes(buffer);var decoded=new DynamicLightPacket();decoded.fromBytes(buffer);
            assertNull(decoded.field);assertEquals(DynamicLights.Mode.OFF,decoded.mode);assertEquals(77,decoded.session);
        } finally { buffer.release(); }
    }
}
