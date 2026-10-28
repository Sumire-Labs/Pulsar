package com.sumirelabs.pulsar.rgb;

import com.sumirelabs.pulsar.light.engine.*;
import com.sumirelabs.pulsar.memory.MemoryGraph;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import net.minecraft.init.Bootstrap;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import java.util.Arrays;

/** Owned-object heap sizes and publication CPU/allocation, not whole-game RSS. */
public final class MemoryFootprintProbe {
    private static volatile Object sink;
    public static void main(String[] args)throws Exception {
        Bootstrap.register();
        var mods=net.minecraftforge.fml.common.Loader.class.getDeclaredField("namedMods");mods.setAccessible(true);mods.set(net.minecraftforge.fml.common.Loader.instance(),java.util.Map.of());
        World world=inert(WorldServer.class);
        for(int sections:new int[]{16,64}) {
            var height=WorldHeightContext.contiguous(0,sections-1);
            report("scalar-engines-"+sections,new Object[]{new ScalarBlockEngine(world,height),new ScalarSkyEngine(world,height)});
            report("rgb-engines-"+sections,new Object[]{new RgbBlockEngine(world,height),new RgbSkyEngine(world,height)});
            report("rgb-storage-"+sections,new RgbStorage(height.getMinLightSection(),height.getTotalLightSections()));
        }
        // Alfheim main's LightingEngine constructs 36 pairs at capacity 16384.
        // Measure those buffers with this runtime's fastutil; not Alfheim's full heap.
        Object[] alfheimQueues=new Object[72];
        for(int i=0;i<36;i++) {
            alfheimQueues[i*2]=new it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue(16384);
            alfheimQueues[i*2+1]=new it.unimi.dsi.fastutil.longs.LongOpenHashSet(16384);
        }
        report("alfheim-initial-queue-model",alfheimQueues);
        var scalarMetadata=new com.sumirelabs.pulsar.light.ContextualLightManager(null,WorldHeightContext.VANILLA);
        var rgbMetadata=new RgbContextualLightManager(null,WorldHeightContext.VANILLA);
        for(int i=0;i<4096;i++) {
            var chunk=new net.minecraft.world.chunk.Chunk(null,i,0);
            scalarMetadata.load(chunk);rgbMetadata.load(chunk,null);
        }
        report("scalar-metadata-4096-empty-chunks",scalarMetadata);
        report("rgb-metadata-4096-empty-chunks",rgbMetadata);
        for(String kind:new String[]{"empty","gray","colored","flat","sky"}) {
            byte[][][] live=planes(kind);
            RgbSnapshot[] snapshots=new RgbSnapshot[256];
            for(int i=0;i<snapshots.length;i++)snapshots[i]=RgbSnapshot.captureMax(-1,i,live,null);
            report("snapshots-256-"+kind,snapshots);
            var copied=new com.sumirelabs.pulsar.light.SWMRNibbleArray[256][18];
            var shared=new com.sumirelabs.pulsar.light.SWMRNibbleArray[256][18];
            for(int i=0;i<256;i++)for(int s=0;s<18;s++) {
                copied[i][s]=new com.sumirelabs.pulsar.light.SWMRNibbleArray(snapshots[i].canonicalSection(s));
                shared[i][s]=snapshots[i].canonicalNibble(s);
            }
            report("snapshot-plus-canonical-copy-"+kind,new Object[]{snapshots,copied});
            report("snapshot-plus-canonical-shared-"+kind,new Object[]{snapshots,shared});
            benchmark(kind,live);
        }
    }
    private static void report(String name,Object root){System.out.println("MEMORY "+name+" bytes="+MemoryGraph.bytes(root));}
    private static byte[][][] planes(String kind) {
        byte[][][] data=new byte[3][18][];
        if(kind.equals("empty"))return data;
        for(int c=0;c<3;c++)for(int s=0;s<18;s++) {
            byte[] bytes=data[c][s]=new byte[2048];
            for(int i=0;i<2048;i++) {
                int low=(i+s)&15,high=(i*3+s)&15;
                if(kind.equals("sky"))low=high=15;
                if(kind.equals("flat"))low=high=7;
                if(kind.equals("colored")){int strength=c==0?15:c==1?9:4;low=(low*strength+7)/15;high=(high*strength+7)/15;}
                bytes[i]=(byte)(low|high<<4);
            }
        }
        return data;
    }
    private static void benchmark(String name,byte[][][] live) {
        var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        for(int i=0;i<300;i++)sink=RgbSnapshot.captureMax(-1,i,live,null);
        long[] nanos=new long[7],allocations=new long[7];
        for(int round=0;round<7;round++) {
            long before=bean.getCurrentThreadAllocatedBytes(),start=System.nanoTime();
            for(int i=0;i<256;i++)sink=RgbSnapshot.captureMax(-1,i,live,null);
            nanos[round]=System.nanoTime()-start;allocations[round]=bean.getCurrentThreadAllocatedBytes()-before;
        }
        Arrays.sort(nanos);Arrays.sort(allocations);
        System.out.println("PUBLICATION "+name+" medianNs="+nanos[3]+" allocatedBytes="+allocations[3]);
    }
    private static <T>T inert(Class<T> type)throws Exception {
        var u=Class.forName("sun.misc.Unsafe");var f=u.getDeclaredField("theUnsafe");f.setAccessible(true);
        return type.cast(u.getMethod("allocateInstance",Class.class).invoke(f.get(null),type));
    }
}
