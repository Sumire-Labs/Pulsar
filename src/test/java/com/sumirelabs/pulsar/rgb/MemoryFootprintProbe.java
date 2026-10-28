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
        benchmarkSkyImport();
        benchmarkClientSky();
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
    private static void benchmarkSkyImport() {
        var height=WorldHeightContext.VANILLA;
        var canonical=new com.sumirelabs.pulsar.light.SWMRNibbleArray[height.getTotalLightSections()];
        for(int s=0;s<canonical.length;s++) {
            byte[] data=new byte[2048];for(int i=0;i<data.length;i++)data[i]=(byte)(i+s);
            canonical[s]=com.sumirelabs.pulsar.light.SWMRNibbleArray.fromOwned(data,com.sumirelabs.pulsar.light.SWMRNibbleArray.INIT_STATE_INIT);
        }
        var empty=new boolean[height.getTotalSections()];
        var stores=new RgbStorage[256];
        for(int i=0;i<stores.length;i++) {
            var separate=new com.sumirelabs.pulsar.light.SWMRNibbleArray[canonical.length];
            for(int s=0;s<separate.length;s++)separate[s]=com.sumirelabs.pulsar.light.SWMRNibbleArray.fromOwned(
                    canonical[s].getVisibleData().clone(),com.sumirelabs.pulsar.light.SWMRNibbleArray.INIT_STATE_INIT);
            stores[i]=RgbStorage.fromSky(height,separate,empty);
        }
        report("sky-import-256-gradient",stores);
        var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        for(int i=0;i<300;i++)sink=RgbStorage.fromSky(height,canonical,empty);
        long[] time=new long[7],allocated=new long[7];
        for(int round=0;round<7;round++) {
            long before=bean.getCurrentThreadAllocatedBytes(),start=System.nanoTime();
            for(int i=0;i<256;i++)sink=RgbStorage.fromSky(height,canonical,empty);
            time[round]=System.nanoTime()-start;allocated[round]=bean.getCurrentThreadAllocatedBytes()-before;
        }
        Arrays.sort(time);Arrays.sort(allocated);
        System.out.println("SKY_IMPORT medianNs="+time[3]+" allocatedBytes="+allocated[3]);
        byte[] changed=new byte[2048];for(int i=0;i<changed.length;i++)changed[i]=(byte)~i;
        for(int writes:new int[]{1,3})for(boolean optimized:new boolean[]{false,true}) {
            for(int i=0;i<300;i++)sink=importAndWrite(height,canonical,empty,changed,writes,optimized);
            for(int round=0;round<7;round++) {
                long before=bean.getCurrentThreadAllocatedBytes(),start=System.nanoTime();
                for(int i=0;i<256;i++)sink=importAndWrite(height,canonical,empty,changed,writes,optimized);
                time[round]=System.nanoTime()-start;allocated[round]=bean.getCurrentThreadAllocatedBytes()-before;
            }
            Arrays.sort(time);Arrays.sort(allocated);
            System.out.println("SKY_IMPORT writes="+writes+" optimized="+optimized+" medianNs="+time[3]+" allocatedBytes="+allocated[3]);
        }
    }
    private static RgbStorage importAndWrite(WorldHeightContext height,com.sumirelabs.pulsar.light.SWMRNibbleArray[] canonical,
                                             boolean[] empty,byte[] changed,int writes,boolean optimized) {
        RgbStorage result;
        if(optimized)result=RgbStorage.fromSky(height,canonical,empty);
        else {
            // Previous nonuniform fromSky path, with the same constructor and pool.
            result=new RgbStorage(height.getMinLightSection(),height.getTotalLightSections());
            for(int c=0;c<3;c++) {
                result.empty[c]=empty.clone();
                for(int s=0;s<canonical.length;s++)result.channels[c][s]=new com.sumirelabs.pulsar.light.SWMRNibbleArray(canonical[s].getVisibleData().clone());
            }
        }
        for(int c=0;c<writes;c++)for(var plane:result.channels[c]){plane.bulkWriteAll(changed);plane.updateVisible();}
        return result;
    }
    private static void benchmarkClientSky() {
        var height=WorldHeightContext.VANILLA;
        var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        for(String shape:new String[]{"open","roof","dense"}) {
            var sky=new com.sumirelabs.pulsar.light.SWMRNibbleArray[height.getTotalLightSections()];
            if(!shape.equals("open"))for(int s=0;s<sky.length;s++) {
                if(shape.equals("roof") && s!=sky.length-2)continue;
                byte[] bytes=new byte[2048];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)(i*19);
                sky[s]=new com.sumirelabs.pulsar.light.SWMRNibbleArray(bytes);
            }
            for(boolean optimized:new boolean[]{false,true}) {
                byte[][][] views=new byte[256][][];
                for(int i=0;i<views.length;i++)views[i]=optimized?RgbStorage.snapshotSky(sky):legacySky(height,sky);
                report("client-sky-"+shape+"-"+optimized,views);
                for(int i=0;i<300;i++)sink=optimized?RgbStorage.snapshotSky(sky):legacySky(height,sky);
                long[] time=new long[7],allocations=new long[7];
                for(int round=0;round<7;round++) {
                    long before=bean.getCurrentThreadAllocatedBytes(),start=System.nanoTime();
                    for(int i=0;i<256;i++)sink=optimized?RgbStorage.snapshotSky(sky):legacySky(height,sky);
                    time[round]=System.nanoTime()-start;allocations[round]=bean.getCurrentThreadAllocatedBytes()-before;
                }
                Arrays.sort(time);Arrays.sort(allocations);
                System.out.println("CLIENT_SKY "+shape+" optimized="+optimized+" medianNs="+time[3]+" allocatedBytes="+allocations[3]);
            }
        }
    }
    /** Previous RgbClient.refresh fallback loop, isolated from Minecraft client startup. */
    private static byte[][] legacySky(WorldHeightContext height,com.sumirelabs.pulsar.light.SWMRNibbleArray[] sky) {
        byte[][] copy=new byte[sky.length][];
        for(int i=0;i<copy.length;i++) {
            var nibble=sky[i];
            if(nibble!=null && !nibble.isNullNibbleVisible()) {
                byte[] bytes=nibble.getVisibleData();if(bytes!=null)copy[i]=bytes.clone();continue;
            }
            byte[] bytes=new byte[2048];int y=(height.getMinLightSection()+i)<<4;
            for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                int index=x|z<<4,value=com.sumirelabs.pulsar.light.ChunkLightHelper.getSkyLight(height,sky,x,y,z);
                bytes[index>>1]|=(byte)(value<<((index&1)*4));
            }
            for(int row=1;row<16;row++)System.arraycopy(bytes,0,bytes,row*128,128);
            copy[i]=bytes;
        }
        return copy;
    }
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
