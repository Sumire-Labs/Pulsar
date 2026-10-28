package com.sumirelabs.pulsar.light.engine;

import com.sumirelabs.pulsar.util.WorldHeightContext;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import java.util.Arrays;

/** Isolated JVM per variant: actual engine accessor, identical checksums. */
public final class EmptySectionProbe {
    private static volatile int sink;
    public static void main(String[] args) throws Exception {
        Bootstrap.register();
        var type=Class.forName("sun.misc.Unsafe");var singleton=type.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        World world=(World)type.getMethod("allocateInstance",Class.class).invoke(singleton.get(null),WorldServer.class);
        var engine=new ScalarBlockEngine(world,WorldHeightContext.VANILLA);
        engine.setupEncodeOffset(7,64,7);
        var section=new ExtendedBlockStorage(64,true);
        engine.setChunkSectionInCache(0,4,0,section);
        int index=100+engine.chunkSectionIndexOffset;
        for(boolean populated:new boolean[]{false,true}) {
            if(populated)for(int i=0;i<4096;i++)section.set(i&15,i>>>8,i>>>4&15,(i&1)==0?Blocks.STONE.getDefaultState():Blocks.AIR.getDefaultState());
            for(int i=0;i<5;i++)read(engine,index,1_000_000);
            long[] samples=new long[9];
            for(int i=0;i<samples.length;i++) {
                long start=System.nanoTime();int count=read(engine,index,4_096_000);samples[i]=System.nanoTime()-start;
                if(count!=(populated?2_048_000:0))throw new AssertionError("Accessor changed block states");
            }
            Arrays.sort(samples);
            System.out.println("EMPTY_ACCESS fast="+Boolean.getBoolean("pulsar.light.emptySectionFastPath")+" populated="+populated+" medianNs="+samples[4]+" checksum="+sink);
        }
        section.set(1,1,1,Blocks.GLOWSTONE.getDefaultState());
        if(engine.getBlockStateFast(index,1,1,1)!=Blocks.GLOWSTONE.getDefaultState())throw new AssertionError("New light source missed");
    }
    private static int read(ScalarBlockEngine engine,int index,int count) {
        int result=0;
        for(int i=0;i<count;i++)if(engine.getBlockStateFast(index,i&15,i>>>8&15,i>>>4&15).getBlock()!=Blocks.AIR)result++;
        sink=result;return result;
    }
}
