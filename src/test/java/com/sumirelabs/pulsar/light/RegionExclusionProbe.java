package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.api.ExtendedWorld;
import com.sumirelabs.pulsar.light.engine.FaceOcclusion;
import com.sumirelabs.pulsar.light.engine.PulsarEngine;
import com.sumirelabs.pulsar.light.engine.ScalarBlockEngine;
import com.sumirelabs.pulsar.light.engine.ScalarSkyEngine;
import com.sumirelabs.pulsar.light.nativeprobe.RegionWriteAudit;
import com.sumirelabs.pulsar.util.CoordinateUtils;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.BlockStairs;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.EnumFacing;
import net.minecraft.world.*;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.fml.common.Loader;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Production engine + transformed Chunk integration; no game save or renderer required. */
public final class RegionExclusionProbe {
    private static final WorldHeightContext HEIGHT=WorldHeightContext.VANILLA;
    private static final class ProbeWorld extends WorldServer implements ExtendedWorld {
        Map<Long,Chunk> chunks;
        WorldHeightContext height;
        int offset;
        volatile Runnable onPublication;
        ProbeWorld(){super(null,null,null,0,null);}
        public Chunk pulsar$getAnyChunkImmediately(int x,int z){
            Runnable hook=onPublication;
            if(hook!=null && x==0 && z==0 && StackWalker.getInstance().walk(s->s.anyMatch(f->f.getMethodName().equals("publishCacheNibble")))) {
                onPublication=null;hook.run();
            }
            return chunks.get(CoordinateUtils.getChunkKey(x,z));
        }
        public boolean pulsar$hasChunkPendingLight(int x,int z){return false;}
        public void pulsar$flushLightUpdates(){}
    }
    private static final class Job {
        final int x,z;
        final ProbeWorld world;
        final ScalarBlockEngine block;
        final ScalarSkyEngine sky;
        final IntOpenHashSet changes;
        Job(ProbeWorld world,int x,int z){
            this.world=world;this.x=x;this.z=z;block=new ScalarBlockEngine(world,world.height);sky=new ScalarSkyEngine(world,world.height);
            changes=new IntOpenHashSet(new int[]{((96+world.offset)<<8)|(8<<4)|8,((80+world.offset)<<8)|(8<<4)|8});
        }
        void run(boolean generation){
            RegionWriteAudit.enter(x,z);
            try {
                if(generation) {
                    Chunk chunk=world.pulsar$getAnyChunkImmediately(x,z);
                    Boolean[] empty=new Boolean[world.height.getTotalSections()];
                    for(int sy=world.height.getMinSection();sy<=world.height.getMaxSection();sy++) {
                        var storage=chunk.getBlockStorageArray()[world.height.getStorageIndex(sy)];
                        empty[world.height.getSectionIndex(sy)]=storage==null||storage.isEmpty();
                    }
                    block.light(chunk,empty.clone(),true);sky.light(chunk,empty.clone(),true);
                } else {
                    block.blocksChangedInChunk(x,z,changes,null);sky.blocksChangedInChunk(x,z,changes,null);
                }
                if(block.wasQueueOverflowed()||sky.wasQueueOverflowed())throw new AssertionError("Queue overflow");
            } finally { RegionWriteAudit.leave(); }
        }
    }
    private record Result(long[] times,long[] hashes,int parallelism,long finalHash){}

    public static void main(String[] args)throws Exception {
        Bootstrap.register();var mods=Loader.class.getDeclaredField("namedMods");mods.setAccessible(true);
        if(mods.get(Loader.instance())==null)mods.set(Loader.instance(),new HashMap<>());
        FaceOcclusion.registerDefaults();
        if(Boolean.getBoolean("pulsar.region.benchmark")) {
            int footprint=Integer.getInteger("pulsar.region.distance",4);
            for(int spacing:new int[]{0,4,5})for(boolean shapes:new boolean[]{false,true}) {
                Result result=Boolean.getBoolean("pulsar.region.scheduler")?runScheduler(footprint,spacing,shapes)
                        :run(footprint,spacing,shapes,40,80,false,false);
                Arrays.sort(result.times);
                System.out.println("REGION distance="+footprint+" spacing="+spacing+" shapes="+shapes
                        +" medianNs="+result.times[result.times.length/2]+" p95Ns="+result.times[result.times.length*95/100]
                        +" maxJobs="+result.parallelism+" hash="+result.finalHash);
            }
        } else {
            int rounds=Integer.getInteger("pulsar.region.rounds",8);
            for(int spacing:new int[]{0,4,5})for(boolean shapes:new boolean[]{false,true}) {
                Result serial=run(4,spacing,shapes,0,rounds,true,false), candidate=run(3,spacing,shapes,0,rounds,true,false);
                if(!Arrays.equals(serial.hashes,candidate.hashes))throw new AssertionError("Serial/concurrent light hashes differ: "+spacing+" "+shapes);
                if(spacing==4 && (serial.parallelism!=1||candidate.parallelism<2))throw new AssertionError("Concurrency control did not exercise overlap");
                System.out.println("REGION_EQUAL spacing="+spacing+" shapes="+shapes+" hashes="+Arrays.toString(candidate.hashes)
                        +" maxJobs="+serial.parallelism+"/"+candidate.parallelism);
            }
            Result extendedSerial=run(4,4,true,0,rounds,true,true), extendedConcurrent=run(3,4,true,0,rounds,true,true);
            if(!Arrays.equals(extendedSerial.hashes,extendedConcurrent.hashes))throw new AssertionError("Extended-height light hashes differ");
            System.out.println("REGION_EXTENDED_EQUAL minY=-64 maxY=319 hashes="+Arrays.toString(extendedConcurrent.hashes));
            publicationLifecycle(false);publicationLifecycle(true);
            System.out.println("REGION_CHECK_PASS audit="+Boolean.getBoolean("pulsar.region.audit")+" writes="+RegionWriteAudit.writes.get()+" rounds="+rounds);
        }
    }

    private static Result runScheduler(int distance,int spacing,boolean shapes)throws Exception {
        com.sumirelabs.pulsar.config.PulsarConfig.features.experimentalServerLightThreads=4;
        com.sumirelabs.pulsar.config.PulsarConfig.performance.conservativeLightTaskReservations=distance==4;
        ProbeWorld world=world();world.height=HEIGHT;world.offset=0;
        int extent=Math.max(4,spacing+2);
        for(int z=-2;z<=extent;z++)for(int x=-2;x<=extent;x++)addChunk(world,x,z,shapes);
        List<Job> jobs=new ArrayList<>();
        for(int i=0;i<4;i++)jobs.add(new Job(world,(i&1)*spacing,(i>>1)*spacing));
        for(Job job:jobs)job.run(true);
        LightQueue blockQueue=new LightQueue(HEIGHT,true),skyQueue=new LightQueue(HEIGHT);
        var active=new AtomicInteger();var maximum=new AtomicInteger();var tasks=new AtomicInteger();
        var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        java.util.function.BiConsumer<ChunkTasks,PulsarEngine> processor=(task,engine)->{
            int x=CoordinateUtils.getChunkX(task.chunkCoordinate),z=CoordinateUtils.getChunkZ(task.chunkCoordinate);
            maximum.accumulateAndGet(active.incrementAndGet(),Math::max);tasks.incrementAndGet();RegionWriteAudit.enter(x,z);
            try {
                engine.blocksChangedInChunk(x,z,task.changedPositions,task.changedSectionSet);
                if(engine.wasQueueOverflowed())throw new AssertionError("Pipeline overflow");
            }catch(Throwable error){failure.compareAndSet(null,error);throw error;}
            finally{RegionWriteAudit.leave();active.decrementAndGet();}
        };
        LightEngineWorker blockWorker=new LightEngineWorker(blockQueue,()->new ScalarBlockEngine(world,HEIGHT),processor,
                new AtomicInteger(),"probe-block","probe-block",true,world,new AtomicInteger());
        LightEngineWorker skyWorker=new LightEngineWorker(skyQueue,()->new ScalarSkyEngine(world,HEIGHT),processor,
                new AtomicInteger(),"probe-sky","probe-sky",true,world,new AtomicInteger());
        blockWorker.pairWith(skyWorker);
        int warmup=Integer.getInteger("pulsar.region.warmup",1000),measured=Integer.getInteger("pulsar.region.samples",300);
        long[] times=new long[measured];int measuredTasks=0;
        try {
            for(int round=-warmup;round<measured;round++) {
                boolean open=(round&1)==0;for(Job job:jobs)edit(world,job.x,job.z,open);
                int before=tasks.get();long start=System.nanoTime();
                // Publish the complete batch before either lane can claim it.
                synchronized(blockQueue){synchronized(skyQueue){
                    for(Job job:jobs)for(int packed:job.changes) {
                        int x=(job.x<<4)|(packed&15),y=packed>>8,z=(job.z<<4)|((packed>>4)&15);
                        blockQueue.queueBlockChange(x,y,z);skyQueue.queueBlockChange(x,y,z);
                    }
                }}
                for(Job job:jobs)for(LightQueue queue:new LightQueue[]{blockQueue,skyQueue}) {
                    long key=CoordinateUtils.getChunkKey(job.x,job.z);
                    for(Future<Void> future;(future=queue.getPendingWorkFuture(key))!=null;)future.get(5,TimeUnit.SECONDS);
                }
                long elapsed=System.nanoTime()-start;
                if(failure.get()!=null)throw new AssertionError("Shared pipeline failed",failure.get());
                if(round>=0){times[round]=elapsed;measuredTasks+=tasks.get()-before;}
                for(Job job:jobs)checkPoints(world,job.x,job.z,open);
            }
        }finally{blockWorker.requestStop();skyWorker.requestStop();blockWorker.awaitStop();skyWorker.awaitStop();}
        System.out.println("PIPELINE warmup="+warmup+" samples="+measured+" tasksPerBatch="+(measuredTasks/(double)measured)
                +" spacing="+spacing+" shapes="+shapes);
        return new Result(times,new long[0],maximum.get(),hash(world,false));
    }

    private static Result run(int distance,int spacing,boolean shapes,int warmup,int measured,boolean verify,boolean extended)throws Exception {
        ProbeWorld world=world();world.height=extended?WorldHeightContext.contiguous(-4,19):HEIGHT;world.offset=extended?-112:0;
        int extent=Math.max(4,spacing+2);
        for(int z=-2;z<=extent;z++)for(int x=-2;x<=extent;x++)addChunk(world,x,z,shapes);
        List<Job> jobs=new ArrayList<>();
        for(int i=0;i<4;i++)jobs.add(new Job(world,(i&1)*spacing,(i>>1)*spacing));
        ChunkTaskReservations reservations=new ChunkTaskReservations(distance);
        AtomicInteger maximum=new AtomicInteger();
        long[] times=new long[measured],hashes=new long[verify?measured:0];
        try(var pool=Executors.newFixedThreadPool(4)) {
            for(int round=-warmup;round<measured;round++) {
                boolean open=(round&1)==0;
                boolean generation=round==-warmup;
                // Make the overlap audit deterministic even on single-core CI:
                // compatible jobs must all hold reservations before writing.
                CountDownLatch startGate=generation && spacing>=4 && (distance==3||spacing>=5)
                        ?new CountDownLatch(4):null;
                for(Job job:jobs)edit(world,job.x,job.z,open);
                long start=System.nanoTime();
                var work=new ArrayList<Callable<Void>>();
                for(Job job:jobs)work.add(()->{
                    long key=CoordinateUtils.getChunkKey(job.x,job.z);
                    synchronized(reservations){
                        while(!reservations.available(world,key))reservations.wait();
                        reservations.reserve(world,key);
                        maximum.accumulateAndGet(reservations.activeJobs(world),Math::max);
                    }
                    try {
                        if(startGate!=null){startGate.countDown();if(!startGate.await(10,TimeUnit.SECONDS))throw new AssertionError("Compatible jobs could not reserve together");}
                        job.run(generation);
                    }
                    finally {synchronized(reservations){reservations.release(world,key);reservations.notifyAll();}}
                    return null;
                });
                for(var future:pool.invokeAll(work))future.get();
                if(round>=0)times[round]=System.nanoTime()-start;
                for(Job job:jobs)checkPoints(world,job.x,job.z,open);
                if(round>=0&&verify)hashes[round]=hash(world,true);
            }
        }
        return new Result(times,hashes,maximum.get(),hash(world,verify));
    }

    private static void addChunk(ProbeWorld world,int x,int z,boolean shapes)throws Exception {
        Chunk chunk=new Chunk(world,x,z);world.chunks.put(CoordinateUtils.getChunkKey(x,z),chunk);
        var storageField=Chunk.class.getDeclaredField("storageArrays");storageField.setAccessible(true);
        storageField.set(chunk,new ExtendedBlockStorage[world.height.getTotalSections()]);
        int floorSection=(64+world.offset)>>4,roofSection=(96+world.offset)>>4,middleSection=(80+world.offset)>>4;
        var floor=new ExtendedBlockStorage(floorSection*16,true);var roof=new ExtendedBlockStorage(roofSection*16,true);
        chunk.getBlockStorageArray()[world.height.getStorageIndex(floorSection)]=floor;
        chunk.getBlockStorageArray()[world.height.getStorageIndex(roofSection)]=roof;
        for(int a=0;a<16;a++)for(int b=0;b<16;b++){floor.set(a,0,b,Blocks.STONE.getDefaultState());roof.set(a,0,b,Blocks.STONE.getDefaultState());}
        if(shapes){
            var middle=new ExtendedBlockStorage(middleSection*16,true);chunk.getBlockStorageArray()[world.height.getStorageIndex(middleSection)]=middle;
            for(int a=0;a<16;a++) {
                middle.set(a,3,4,Blocks.WATER.getDefaultState());
                middle.set(a,2,5,Blocks.STONE_SLAB.getDefaultState().withProperty(BlockSlab.HALF,BlockSlab.EnumBlockHalf.TOP));
                middle.set(a,1,6,Blocks.STONE_STAIRS.getDefaultState().withProperty(BlockStairs.FACING,EnumFacing.WEST));
            }
        }
        PulsarChunk light=(PulsarChunk)chunk;
        boolean[] empty=new boolean[world.height.getTotalSections()];Arrays.fill(empty,true);
        empty[world.height.getSectionIndex(floorSection)]=empty[world.height.getSectionIndex(roofSection)]=false;
        if(shapes)empty[world.height.getSectionIndex(middleSection)]=false;
        light.pulsar$setBlockEmptinessMap(empty.clone());light.pulsar$setSkyEmptinessMap(empty.clone());
        light.pulsar$setBlockNibbles(new SWMRNibbleArray[world.height.getTotalLightSections()]);
        light.pulsar$setSkyNibbles(new SWMRNibbleArray[world.height.getTotalLightSections()]);
        for(int sy=world.height.getMinLightSection();sy<=world.height.getMaxLightSection();sy++) {
            SWMRNibbleArray block=new SWMRNibbleArray();block.setZero();block.updateVisible();
            SWMRNibbleArray sky=new SWMRNibbleArray();
            if(sy>=roofSection){sky.setFull();if(sy==roofSection)for(int a=0;a<16;a++)for(int b=0;b<16;b++)sky.set(a,0,b,0);}
            else sky.setZero();sky.updateVisible();
            int index=world.height.getLightSectionIndex(sy);
            light.pulsar$getBlockNibbles()[index]=block;light.pulsar$getSkyNibbles()[index]=sky;
        }
        light.pulsar$setLightUsable(true);light.pulsar$setLightReady(true);
    }
    private static void edit(ProbeWorld world,int x,int z,boolean open) {
        Chunk chunk=world.pulsar$getAnyChunkImmediately(x,z);
        int roof=world.height.getStorageIndex((96+world.offset)>>4),middle=world.height.getStorageIndex((80+world.offset)>>4);
        chunk.getBlockStorageArray()[roof].set(8,0,8,(open?Blocks.AIR:Blocks.STONE).getDefaultState());
        if(chunk.getBlockStorageArray()[middle]==null)chunk.getBlockStorageArray()[middle]=new ExtendedBlockStorage(80+world.offset,true);
        chunk.getBlockStorageArray()[middle].set(8,0,8,(open?Blocks.GLOWSTONE:Blocks.AIR).getDefaultState());
    }
    private static void checkPoints(ProbeWorld world,int x,int z,boolean open) {
        PulsarChunk chunk=(PulsarChunk)world.pulsar$getAnyChunkImmediately(x,z);
        int block=ChunkLightHelper.getBlockLight(world.height,chunk.pulsar$getBlockNibbles(),8,80+world.offset,8);
        int sky=ChunkLightHelper.getSkyLight(world.height,chunk.pulsar$getSkyNibbles(),8,95+world.offset,8);
        if(block!=(open?15:0)||sky!=(open?15:0))throw new AssertionError("Independent point check: "+block+"/"+sky+" open="+open);
    }
    private static long hash(ProbeWorld world,boolean fullHeight) {
        long result=0xcbf29ce484222325L;
        for(Chunk chunk:world.chunks.values()) {
            var light=(PulsarChunk)chunk;
            int first=fullHeight?world.height.getMinBlockY():48+world.offset;
            int limit=fullHeight?world.height.getMaxBlockY()+1:112+world.offset;
            for(int y=first;y<limit;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                result=(result^ChunkLightHelper.getBlockLight(world.height,light.pulsar$getBlockNibbles(),x,y,z))*0x100000001b3L;
                result=(result^ChunkLightHelper.getSkyLight(world.height,light.pulsar$getSkyNibbles(),x,y,z))*0x100000001b3L;
            }
        }
        return result;
    }
    private static void publicationLifecycle(boolean replace)throws Exception {
        ProbeWorld world=world();world.height=HEIGHT;world.offset=0;world.chunks=new ConcurrentHashMap<>();
        for(int z=-2;z<=2;z++)for(int x=-2;x<=2;x++)addChunk(world,x,z,false);
        Chunk original=world.pulsar$getAnyChunkImmediately(0,0);
        edit(world,0,0,true);
        world.onPublication=()->{
            world.chunks.remove(CoordinateUtils.getChunkKey(0,0));
            if(replace)try{addChunk(world,0,0,false);}catch(Exception e){throw new IllegalStateException(e);}
        };
        new Job(world,0,0).run(false);
        if(world.onPublication!=null)throw new AssertionError("Publication lifecycle hook not reached");
        PulsarChunk old=(PulsarChunk)original;
        if(ChunkLightHelper.getBlockLight(HEIGHT,old.pulsar$getBlockNibbles(),8,80,8)!=0)
            throw new AssertionError("Detached chunk received stale visible light");
        if(replace)checkPoints(world,0,0,false);
        System.out.println("REGION_LIFECYCLE_PASS replacement="+replace);
    }
    private static ProbeWorld world()throws Exception {
        var unsafe=Class.forName("sun.misc.Unsafe");var singleton=unsafe.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        var world=(ProbeWorld)unsafe.getMethod("allocateInstance",Class.class).invoke(singleton.get(null),ProbeWorld.class);
        world.chunks=new LinkedHashMap<>();
        var provider=World.class.getDeclaredField("provider");provider.setAccessible(true);
        provider.set(world,new WorldProvider(){public DimensionType getDimensionType(){return DimensionType.OVERWORLD;}public boolean hasSkyLight(){return true;}});
        var info=World.class.getDeclaredField("worldInfo");info.setAccessible(true);
        info.set(world,new WorldInfo(new WorldSettings(1L,GameType.CREATIVE,false,false,WorldType.FLAT),"region-probe"));
        return world;
    }
}
