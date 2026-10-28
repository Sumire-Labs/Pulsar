package com.sumirelabs.pulsar.rgb;

import com.sumirelabs.pulsar.light.SWMRNibbleArray;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RgbSkyImportTest {
    @Test void channelsShareOneCaptureButWritesAndSourceMutationsAreIsolated() {
        var height=WorldHeightContext.contiguous(0,0);
        var canonical=new SWMRNibbleArray[height.getTotalLightSections()];
        byte[] original=new byte[2048];java.util.Arrays.fill(original,(byte)0x37);
        canonical[1]=SWMRNibbleArray.fromOwned(original,SWMRNibbleArray.INIT_STATE_INIT);
        var storage=RgbStorage.fromSky(height,canonical,new boolean[]{false});
        byte[] shared=storage.channels[0][1].getVisibleData();
        assertSame(original,shared);
        for(int c=0;c<3;c++)assertSame(shared,storage.channels[c][1].getVisibleData());
        canonical[1].set(0,0);canonical[1].updateVisible();
        for(int c=0;c<3;c++)assertEquals(7,storage.channels[c][1].getVisible(0));
        storage.channels[1][1].set(0,12);storage.channels[1][1].updateVisible();
        assertEquals(7,storage.channels[0][1].getVisible(0));assertEquals(12,storage.channels[1][1].getVisible(0));
        assertEquals(7,storage.channels[2][1].getVisible(0));assertEquals(0x37,shared[0]&255);
        assertNotSame(storage.empty[0],storage.empty[1],"Legacy channel topology must stay independent");
    }
    @Test void externallyOwnedVanillaStorageKeepsIndependentWritableCopies() {
        var height=WorldHeightContext.contiguous(0,0);var canonical=new SWMRNibbleArray[height.getTotalLightSections()];
        byte[] vanilla=new byte[2048];java.util.Arrays.fill(vanilla,(byte)0x77);canonical[1]=new SWMRNibbleArray(vanilla);
        assertThrows(IllegalStateException.class,()->canonical[1].shareVisibleData());
        var storage=RgbStorage.fromSky(height,canonical,null);
        assertNotSame(storage.channels[0][1].getVisibleData(),storage.channels[1][1].getVisibleData());
        vanilla[0]=0;for(int c=0;c<3;c++)assertEquals(7,storage.channels[c][1].getVisible(0));
    }
    @Test void nullZeroFullAndHiddenSectionStatesKeepTheirSemantics() {
        var height=WorldHeightContext.contiguous(0,1);
        var input=new SWMRNibbleArray[height.getTotalLightSections()];
        input[0]=new SWMRNibbleArray(null,true);
        input[1]=new SWMRNibbleArray();
        input[2]=new SWMRNibbleArray();input[2].setFull();input[2].updateVisible();
        input[3]=new SWMRNibbleArray(new byte[2048]);input[3].setHidden();input[3].updateVisible();
        var result=RgbStorage.fromSky(height,input,null);
        for(int c=0;c<3;c++) {
            assertTrue(result.channels[c][0].isNullNibbleVisible());
            assertTrue(result.channels[c][1].isUninitialisedVisible());
            assertTrue(result.channels[c][2].isFullVisible());
            // fromSky historically materializes hidden visible data as INIT.
            assertTrue(result.channels[c][3].isInitialisedVisible());
        }
    }
}
