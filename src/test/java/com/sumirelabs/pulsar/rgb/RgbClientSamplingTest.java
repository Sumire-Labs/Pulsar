package com.sumirelabs.pulsar.rgb;

import com.sumirelabs.pulsar.util.CoordinateUtils;
import net.minecraft.world.chunk.Chunk;
import org.junit.jupiter.api.*;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RgbClientSamplingTest {
    private static final int MATERIAL=0x7F123456;
    private Map<Long,Object> views;
    private Constructor<?> view;
    private static void flag(String name,Object value)throws Exception{
        Field f=RgbClient.class.getDeclaredField(name);f.setAccessible(true);f.set(null,value);
    }
    @BeforeEach @SuppressWarnings("unchecked") void setup()throws Exception{
        Field f=RgbClient.class.getDeclaredField("VIEWS");f.setAccessible(true);views=(Map<Long,Object>)f.get(null);views.clear();
        view=Class.forName(RgbClient.class.getName()+"$View").getDeclaredConstructor(Chunk.class,RgbSnapshot.class,RgbSnapshot.class,byte[][].class);view.setAccessible(true);
        flag("active",true);flag("skyActive",false);flag("skyScale",1f);
    }
    @AfterEach void clear()throws Exception{views.clear();flag("active",false);flag("skyActive",false);flag("skyScale",1f);}
    private static byte[] plane(int level){byte[] p=new byte[2048];Arrays.fill(p,(byte)(level*17));return p;}
    private static RgbSnapshot snapshot(int[] colors,int[] canonical){
        byte[][][] data=new byte[3][colors.length][];byte[][] scalar=new byte[colors.length][];
        for(int i=0;i<colors.length;i++){scalar[i]=plane(canonical[i]);for(int c=0;c<3;c++)data[c][i]=plane(RgbLight.channel(colors[i],c));}
        return new RgbSnapshot(-1,1,data,scalar);
    }
    private static RgbSnapshot solid(int color,int canonical){return snapshot(new int[]{color,color,color},new int[]{canonical,canonical,canonical});}
    private void put(int x,int z,RgbSnapshot block,RgbSnapshot sky,int... fallback)throws Exception{
        byte[][] scalar=new byte[3][];for(int i=0;i<fallback.length;i++)if(fallback[i]>=0)scalar[i]=plane(fallback[i]);
        views.put(CoordinateUtils.getChunkKey(x,z),view.newInstance(null,block,sky,scalar));
    }
    @Test void interiorRetainsCanonicalAttenuationAndMaterialAlpha()throws Exception{
        put(0,0,solid(RgbLight.pack(9,3,0),12),null);
        int actual=RgbClient.tintAbgr(MATERIAL,6.5,.5,8.5);
        assertEquals(RgbClient.modulateSky(MATERIAL,9,3,0,12,0,0,0,0,1),actual);assertEquals(0x7F,actual>>>24);
    }
    @Test void fourChunkCornerAndNegativeInteriorUseAllAvailableColors()throws Exception{
        put(-1,-1,solid(RgbLight.pack(15,0,0),15),null);put(0,-1,solid(RgbLight.pack(0,12,0),15),null);
        put(-1,0,solid(RgbLight.pack(0,0,7),8),null);put(0,0,solid(RgbLight.pack(3,8,9),11),null);
        assertEquals(RgbClient.modulateSky(MATERIAL,15,12,9,15,0,0,0,0,1),RgbClient.tintAbgr(MATERIAL,0,.5,0));
        assertEquals(RgbClient.modulateSky(MATERIAL,15,0,0,15,0,0,0,0,1),RgbClient.tintAbgr(MATERIAL,-5.5,.5,-5.5));
    }
    @Test void verticalSectionBoundaryCombinesBlockAndColoredSkyIndependently()throws Exception{
        put(0,0,snapshot(new int[]{0,RgbLight.pack(9,4,2),RgbLight.pack(3,12,7)},new int[]{0,10,12}),
                snapshot(new int[]{0,RgbLight.pack(0,15,0),RgbLight.pack(0,0,15)},new int[]{0,15,15}));flag("skyScale",.4f);
        assertEquals(RgbClient.modulateSky(MATERIAL,9,12,7,12,0,15,15,15,.4f),RgbClient.tintAbgr(MATERIAL,5.5,15.5,6.5));
    }
    @Test void missingNeighborsDoNotHideSkyOnlyData()throws Exception{
        flag("active",false);flag("skyActive",true);put(0,0,null,solid(RgbLight.pack(0,15,7),15));
        assertEquals(RgbClient.modulateSky(MATERIAL,0,0,0,0,0,15,7,15,1),RgbClient.tintAbgr(MATERIAL,0,.5,0));
        assertEquals(MATERIAL,RgbClient.tintAbgr(MATERIAL,-32,.5,-32));
    }
    @Test void scalarSkyFallbackIncludesTheUpperSection()throws Exception{
        put(0,0,solid(RgbLight.pack(7,0,0),7),null,4,-1,13);flag("skyScale",.5f);
        assertEquals(RgbClient.modulateSky(MATERIAL,7,0,0,7,13,13,13,13,.5f),RgbClient.tintAbgr(MATERIAL,5.5,15.5,6.5));
    }
}
