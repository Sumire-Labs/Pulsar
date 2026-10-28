package com.sumirelabs.pulsar.rgb;

import com.sumirelabs.pulsar.light.ChunkLightHelper;
import com.sumirelabs.pulsar.light.SWMRNibbleArray;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScalarSkySnapshotTest {
    @Test void unchangedSectionsRetainOwnedArraysAndChangedSectionsStayIsolated() {
        byte[] live=new byte[2048];for(int i=0;i<live.length;i++)live[i]=(byte)i;
        var sky=new SWMRNibbleArray[]{new SWMRNibbleArray(live),new SWMRNibbleArray(live.clone())};
        var first=RgbStorage.snapshotSky(sky);var second=RgbStorage.snapshotSky(sky,first);
        assertSame(first[0],second[0]);assertSame(first[1],second[1]);
        live[0]=0x77;var third=RgbStorage.snapshotSky(sky,second);
        assertNotSame(second[0],third[0]);assertSame(second[1],third[1]);
        assertEquals(0,first[0][0]);assertEquals(0x77,third[0][0]);
    }
    @Test void fallbackMatchesEveryCellAcrossHolesZeroFullAndNegativeHeight() {
        var height=WorldHeightContext.contiguous(-4,7);
        var sky=new SWMRNibbleArray[height.getTotalLightSections()];
        byte[] gradient=new byte[2048];for(int i=0;i<gradient.length;i++)gradient[i]=(byte)(i*31);
        sky[sky.length-2]=new SWMRNibbleArray(gradient);
        sky[4]=new SWMRNibbleArray(); // UNINIT must block extrusion, not become full sky.
        sky[2]=new SWMRNibbleArray();sky[2].setFull();sky[2].updateVisible();
        var captured=RgbStorage.snapshotSky(sky);
        for(int s=0;s<captured.length;s++)for(int local=0;local<4096;local++) {
            int x=local&15,z=local>>>4&15,y=(height.getMinLightSection()+s)*16+(local>>>8);
            int actual=captured[s]==null?0:captured[s][local>>>1]>>>((local&1)*4)&15;
            assertEquals(ChunkLightHelper.getSkyLight(height,sky,x,y,z),actual);
        }
        assertSame(captured[5],captured[6],"Repeated lower-layer extrusion must share its immutable copy");
        byte previous=captured[sky.length-2][0];gradient[0]=127;
        assertEquals(previous,captured[sky.length-2][0],"Live vanilla bytes must not alias the client view");
    }
    @Test void openSkySharesItsConstantPlane() {
        var sky=new SWMRNibbleArray[18];var first=RgbStorage.snapshotSky(sky);var second=RgbStorage.snapshotSky(sky);
        for(int s=0;s<18;s++){assertSame(first[0],first[s]);assertSame(first[0],second[s]);assertEquals(255,first[s][0]&255);}
    }
}
