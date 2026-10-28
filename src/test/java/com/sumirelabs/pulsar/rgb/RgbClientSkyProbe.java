package com.sumirelabs.pulsar.rgb;

import com.sumirelabs.pulsar.util.WorldHeightContext;
import com.sumirelabs.pulsar.util.CoordinateUtils;
import com.sumirelabs.pulsar.world.PulsarWorld;
import com.sumirelabs.pulsar.light.WorldLightManager;
import net.minecraft.world.*;
import net.minecraft.world.chunk.Chunk;

/** Uses transformed chunks and the real client refresh path without opening a window. */
public final class RgbClientSkyProbe {
    private static final class ProbeWorld extends WorldServer implements PulsarWorld {
        ProbeWorld(){super(null,null,null,0,null);}
        public WorldLightManager pulsar$getLightManager(){return null;}
        public void pulsar$shutdown(){}
        public Chunk pulsar$getAnyChunkImmediately(int x,int z){return null;}
        public boolean pulsar$hasChunkPendingLight(int x,int z){return false;}
        public void pulsar$flushLightUpdates(){}
    }
    public static void verify()throws Exception {
        var type=Class.forName("sun.misc.Unsafe");var singleton=type.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        World world=(World)type.getMethod("allocateInstance",Class.class).invoke(singleton.get(null),ProbeWorld.class);
        var remote=World.class.getDeclaredField("isRemote");remote.setAccessible(true);remote.setBoolean(world,true);
        var hasSky=new java.util.concurrent.atomic.AtomicBoolean(true);
        var provider=World.class.getDeclaredField("provider");provider.setAccessible(true);
        provider.set(world,new WorldProvider(){public DimensionType getDimensionType(){return DimensionType.OVERWORLD;}public boolean hasSkyLight(){return hasSky.get();}});
        var chunk=new Chunk(world,32,32);var height=WorldHeightContext.VANILLA;
        var snapshot=new RgbSnapshot(height.getMinLightSection(),1,new byte[3][height.getTotalLightSections()][]);
        ((RgbChunk)chunk).pulsar$setRgbSnapshot(snapshot);
        var field=RgbClient.class.getDeclaredField("VIEWS");field.setAccessible(true);
        @SuppressWarnings("unchecked") var views=(java.util.Map<Long,RgbClient.View>)field.get(null);
        long key=CoordinateUtils.getChunkKey(32,32);
        try {
            RgbClient.refresh(chunk);var fallback=views.get(key).sky();
            check(fallback.length==height.getTotalLightSections(),"Fallback layout changed");
            check((fallback[0][0]&255)==255 && fallback[0]==fallback[1],"Open sky is not shared/full");
            ((RgbChunk)chunk).pulsar$setRgbSkySnapshot(snapshot);
            RgbClient.refresh(chunk);check(views.get(key).sky().length==0,"RGB sky retained unused fallback storage");
            ((RgbChunk)chunk).pulsar$setRgbSkySnapshot(null);hasSky.set(false);
            RgbClient.refresh(chunk);check(views.get(key).sky().length==0,"Skyless dimension retained fallback storage");
        } finally {views.remove(key);}
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
