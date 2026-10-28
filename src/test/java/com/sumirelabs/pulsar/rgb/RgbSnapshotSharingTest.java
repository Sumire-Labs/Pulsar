package com.sumirelabs.pulsar.rgb;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class RgbSnapshotSharingTest {
    @Test void repeatedExtrudedSkyPlanesReuseOneImmutableCopy()throws Exception {
        byte[] sky=gradient();byte[][] column=new byte[18][];Arrays.fill(column,sky);
        var snapshot=RgbSnapshot.capture(-1,1,new byte[][][]{column,column,column},column,null);
        var owned=data(snapshot);
        for(int c=0;c<3;c++)for(int s=0;s<18;s++)assertSame(owned[0][0],owned[c][s]);
        Arrays.fill(sky,(byte)0);assertEquals(0x111,snapshot.get(2,0,0));
    }
    private static byte[] gradient(){byte[] a=new byte[2048];for(int i=0;i<a.length;i++)a[i]=(byte)i;return a;}
    private static byte[][][] data(RgbSnapshot snapshot)throws Exception {
        var field=RgbSnapshot.class.getDeclaredField("data");field.setAccessible(true);return (byte[][][])field.get(snapshot);
    }
    @Test void equalChannelsAndCanonicalShareOwnedBytesWithoutAliasingInputs()throws Exception {
        byte[][][] live={{gradient()},{gradient()},{gradient()}};
        var snapshot=RgbSnapshot.captureMax(0,1,live,null);var owned=data(snapshot);
        assertSame(owned[0][0],owned[1][0]);assertSame(owned[0][0],owned[2][0]);
        assertSame(owned[0][0],snapshot.canonicalNibble(0).getVisibleData());
        int expected=snapshot.get(2,0,0);
        Arrays.fill(live[0][0],(byte)0xFF);Arrays.fill(snapshot.section(1,0),(byte)0);
        assertEquals(expected,snapshot.get(2,0,0));
        var next=RgbSnapshot.captureMax(0,2,live,snapshot);
        assertEquals(expected,snapshot.get(2,0,0));assertEquals(15,next.canonical(2,0,0));
    }
    @Test void publicConstructorAndIndependentCanonicalStayIsolated()throws Exception {
        byte[] gray=gradient(),canonical=new byte[2048];Arrays.fill(canonical,(byte)0xFF);
        var snapshot=new RgbSnapshot(0,1,new byte[][][]{{gray},{gray.clone()},{gray.clone()}},new byte[][]{canonical});
        var owned=data(snapshot);assertSame(owned[0][0],owned[1][0]);
        assertNotSame(owned[0][0],snapshot.canonicalNibble(0).getVisibleData());
        gray[0]=0x55;canonical[0]=0;
        assertEquals(0,snapshot.get(0,0,0));assertEquals(15,snapshot.canonical(0,0,0));
    }
    @Test void constantPlanesShareAcrossChunksButDetachForCanonicalWrites()throws Exception {
        byte[] value=new byte[2048];Arrays.fill(value,(byte)0x77);
        var first=RgbSnapshot.captureMax(0,1,new byte[][][]{{value},{value.clone()},{value.clone()}},null);
        var second=RgbSnapshot.captureMax(0,2,new byte[][][]{{value.clone()},{value.clone()},{value.clone()}},null);
        assertSame(data(first)[0][0],data(second)[0][0]);
        var writable=first.canonicalNibble(0);writable.set(0,3);writable.updateVisible();
        assertEquals(7,first.canonical(0,0,0));assertEquals(7,second.canonical(0,0,0));
        assertEquals(3,writable.getVisible(0));
    }
}
