package com.sumirelabs.pulsar.rgb;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class RgbSnapshotSharingTest {
    @Test void engineAndSnapshotShareEqualPlanesButSubsequentChannelWritesDetach()throws Exception {
        var storage=new RgbStorage(0,1);
        for(int c=0;c<3;c++)storage.channels[c][0]=com.sumirelabs.pulsar.light.SWMRNibbleArray.fromOwned(gradient(),2);
        var first=storage.maximumSnapshot(1,false,null);
        byte[] shared=data(first)[0][0];
        for(int c=0;c<3;c++)assertSame(shared,storage.channels[c][0].getVisibleData());
        int original=first.get(2,0,0);
        storage.channels[0][0].set(2,12);storage.channels[0][0].updateVisible();
        var next=storage.maximumSnapshot(2,false,first);
        assertEquals(original,first.get(2,0,0));
        assertEquals((original&255)|0xC00,next.get(2,0,0));
        assertSame(shared,storage.channels[1][0].getVisibleData());
        assertNotSame(shared,storage.channels[0][0].getVisibleData());
    }
    @Test void adoptingPublishedPlanesPreservesPendingWorkingChanges() {
        var storage=new RgbStorage(0,1);
        for(int c=0;c<3;c++)storage.channels[c][0]=com.sumirelabs.pulsar.light.SWMRNibbleArray.fromOwned(gradient(),2);
        storage.channels[1][0].set(2,13);
        var visible=storage.maximumSnapshot(1,false,null);
        assertEquals(0x111,visible.get(2,0,0));
        assertEquals(13,storage.channels[1][0].getUpdating(2));
        storage.channels[1][0].updateVisible();
        assertEquals(0x1D1,storage.maximumSnapshot(2,false,visible).get(2,0,0));
        assertEquals(0x111,visible.get(2,0,0));
    }
    @Test void externalVanillaBuffersAreStillCopiedAndRetainTheirIdentity() {
        var storage=new RgbStorage(0,1);byte[] external=gradient();
        for(int c=0;c<3;c++)storage.channels[c][0]=new com.sumirelabs.pulsar.light.SWMRNibbleArray(external);
        var visible=storage.maximumSnapshot(1,false,null);
        assertSame(external,storage.channels[0][0].getVisibleData());
        external[1]=0x77;
        assertEquals(0x111,visible.get(2,0,0));
    }
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
