package com.sumirelabs.pulsar.light;

import codechicken.multipart.BlockMultipart;
import codechicken.multipart.TileMultipart;
import codechicken.multipart.handler.MultipartProxy;
import com.sumirelabs.pulsar.Pulsar;
import com.sumirelabs.pulsar.api.*;
import com.sumirelabs.pulsar.compat.*;
import com.sumirelabs.pulsar.core.RgbMixinSelection;
import com.sumirelabs.pulsar.light.engine.LightInfo;
import com.sumirelabs.pulsar.rgb.*;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import com.sumirelabs.pulsar.world.PulsarWorld;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.*;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraftforge.fml.common.Loader;
import java.util.Map;
import java.util.concurrent.Executors;

/** Real CBMultipart + production Mixins/managers; inert world avoids starting a game. */
public final class MultipartLightProbe {
    private static final BlockPos POS = new BlockPos(8,64,8);
    public static class ProbeWorld extends WorldServer implements PulsarWorld, ExtendedWorld {
        WorldLightManager manager;
        Chunk chunk;
        Thread owner;
        int reads;
        com.sumirelabs.pulsar.light.engine.MultipartPropagation propagation;
        ProbeWorld() { super(null,null,null,0,null); }
        @Override public TileEntity getTileEntity(BlockPos pos) {
            if (Thread.currentThread() != owner) throw new AssertionError("Worker accessed live tile");
            reads++;
            return chunk.getTileEntityMap().get(pos);
        }
        @Override public IBlockState getBlockState(BlockPos pos) { return chunk.getBlockState(pos); }
        @Override public void notifyBlockUpdate(BlockPos pos,IBlockState old,IBlockState next,int flags) {}
        @Override public void notifyNeighborsOfStateChange(BlockPos pos,Block block,boolean observers) {}
        @Override public boolean checkLight(BlockPos pos) {
            manager.queueLightCheck(EnumSkyBlock.SKY,pos.getX(),pos.getY(),pos.getZ());
            manager.queueLightCheck(EnumSkyBlock.BLOCK,pos.getX(),pos.getY(),pos.getZ());
            return true;
        }
        public WorldLightManager pulsar$getLightManager() { return manager; }
        public void pulsar$shutdown() { manager.shutdown(); }
        public Chunk pulsar$getAnyChunkImmediately(int x,int z) { return manager.getLoadedChunk(x,z); }
        public boolean pulsar$hasChunkPendingLight(int x,int z) { return manager.hasChunkPendingLight(x,z); }
        public void pulsar$flushLightUpdates() {}
    }
    public static void main(String[] args) throws Exception {
        Bootstrap.register();
        check(java.util.Arrays.stream(World.class.getDeclaredMethods()).anyMatch(m->m.getName().contains("pulsar$traceRender")),"Missing render trace hook");
        if(Boolean.getBoolean("pulsar.traceRenderer")) {
            var loader=MultipartLightProbe.class.getClassLoader();
            var renderer=Class.forName("org.taumc.celeritas.impl.render.terrain.VintageRenderSectionManager",false,loader);
            check(java.util.Arrays.stream(renderer.getDeclaredMethods()).anyMatch(m->m.getName().contains("pulsar$traceSchedule")),"Missing Celeritas trace hook");
            var build=Class.forName("org.taumc.celeritas.impl.render.terrain.compile.task.ChunkBuilderMeshingTask",false,loader);
            check(java.util.Arrays.stream(build.getDeclaredFields()).anyMatch(f->f.getName().equals("pulsar$traceStarted")),"Missing build timer");
        }
        set(net.minecraftforge.fml.relauncher.FMLInjectionData.class,null,"minecraftHome",net.minecraft.launchwrapper.Launch.minecraftHome);
        boolean rgb = RgbMixinSelection.rgbEnabled();
        var named = Loader.class.getDeclaredField("namedMods"); named.setAccessible(true);
        named.set(Loader.instance(), Map.of());
        Pulsar.proxy = new com.sumirelabs.pulsar.proxy.IProxy() {};
        BlockLightColors.defaults(); BlockLightFilters.defaults(); BlockLightColors.freeze();
        com.sumirelabs.pulsar.config.PulsarConfig.rgbGameplayLight = System.getProperty("pulsar.multipartMode").equals("RGB_VANILLA")
                ? RgbSettings.GameplayLight.VANILLA : RgbSettings.GameplayLight.RGB_MAX;
        RgbSettings.initialize();
        MultipartFixtures.material("pulsar:glowstone", Blocks.GLOWSTONE.getDefaultState());
        MultipartFixtures.material("pulsar:lantern", Blocks.SEA_LANTERN.getDefaultState());
        MultipartFixtures.finishMaterials();
        var block = new BlockMultipart();
        check(block instanceof MultipartLightBlock, "Missing common block marker");
        check((block instanceof PositionalColoredLightSource) == rgb, "RGB mixin selection");
        // Register the host so palettes can round-trip its state ID.
        Block.BLOCK_STATE_IDS.put(block.getDefaultState(), 4090 << 4);
        // The real notifier references the singleton's host block.
        var proxy = Class.forName("codechicken.multipart.handler.MultipartProxy$");
        Object singleton = proxy.getField("MODULE$").get(null);
        proxy.getMethod("block_$eq", BlockMultipart.class).invoke(singleton,block);
        ProbeWorld world = inert(ProbeWorld.class);
        world.owner = Thread.currentThread();
        set(World.class,world,"provider",new WorldProviderSurface());
        set(World.class,world,"worldInfo",new net.minecraft.world.storage.WorldInfo(new net.minecraft.nbt.NBTTagCompound()));
        set(World.class,world,"isRemote",true);
        world.manager = new WorldLightManager(world,true,true); // main-thread lanes, no background daemon
        set(World.class,world,"isRemote",false);
        if (rgb) set(WorldLightManager.class,world.manager,"rgbContextualLight",new RgbContextualLightManager(world,WorldHeightContext.VANILLA));
        world.chunk = new Chunk(world,0,0);
        world.chunk.getBlockStorageArray()[4] = new ExtendedBlockStorage(64,true);
        world.chunk.getBlockStorageArray()[4].set(8,0,8,block.getDefaultState());
        var tile = new TileMultipart(); tile.setWorld(world); tile.setPos(POS);
        var glow = MultipartFixtures.part("pulsar:glowstone");
        MultipartFixtures.parts(tile,glow);
        world.chunk.getTileEntityMap().put(POS,tile);
        world.manager.registerChunk(world.chunk);
        clearQueues(world.manager);
        check((LightInfo.of(block.getDefaultState()) & LightInfo.CONTEXT_EMISSION)!=0,"Dynamic zero-strength source misclassified");
        check((((ContextualLightPalette)world.chunk.getBlockStorageArray()[4].getData()).pulsar$lightPaletteFlags()&ContextualLightPalette.MAY_EMIT)!=0,
                "Initial light scan skips Multipart");
        world.propagation=new com.sumirelabs.pulsar.light.engine.MultipartPropagation(world,world.chunk.getBlockStorageArray()[4],rgb);
        assertWorker(world,block.getDefaultState(),15,rgb?BlockLightColors.emission(Blocks.GLOWSTONE.getDefaultState(),15):0,rgb);
        check(!((MultipartLightTile)tile).pulsar$needsLightPolling(),"Standard material must be notification-only");
        int reads = world.reads;
        var tracking=ContextualLightManager.class.getDeclaredField("trackedSources");tracking.setAccessible(true);
        check(((TrackedLightSources<?,?>)tracking.get(world.manager.contextualLight())).size()==0,"Idle materials retain scalar polling entries");
        for(int i=0;i<100;i++) world.manager.publishContextualLight();
        check(world.reads==reads,"Stationary standard parts were polled");

        // Counterfactual: the same host/material and samples, with fallback polling retained.
        set(TileMultipart.class,tile,"pulsar$policyKnown",true);
        set(TileMultipart.class,tile,"pulsar$pollLight",true);
        world.manager.contextualLight().tileEntityChanged(POS);
        if(rgb)world.manager.rgbContextualLight().request(8,64,8);
        world.manager.publishContextualLight(); clearQueues(world.manager);
        int baselineStart=world.reads;
        for(int i=0;i<100;i++)world.manager.publishContextualLight();
        int baselineReads=world.reads-baselineStart;
        check(baselineReads==(rgb?200:100),"Unexpected polling baseline: "+baselineReads);

        // Same host state and same scalar level: color change must still publish.
        MultipartFixtures.parts(tile,MultipartFixtures.part("pulsar:lantern"));
        tile.notifyPartChange(null);
        check(!queue(world.manager,"blockQueue").hasWork(),"BFS queued before samples settled");
        check(world.manager.hasUpdates(),"Deferred checks disappeared from world busy state");
        world.manager.publishContextualLight();
        check(queue(world.manager,"blockQueue").hasWork(),"Missing post-publication work");
        int expected = rgb ? BlockLightColors.emission(Blocks.SEA_LANTERN.getDefaultState(),15) : 0;
        assertWorker(world,block.getDefaultState(),15,expected,rgb);
        clearQueues(world.manager);

        // Unknown custom parts preserve fallback polling and power changes.
        var dynamic = new MultipartFixtures.UnknownPart(6);
        MultipartFixtures.parts(tile,dynamic); tile.notifyPartChange(null);
        world.manager.publishContextualLight(); clearQueues(world.manager);
        check(((MultipartLightTile)tile).pulsar$needsLightPolling(),"Unknown part incorrectly opted out");
        dynamic.level=3; world.manager.publishContextualLight();
        assertWorker(world,block.getDefaultState(),3,0x333,rgb); clearQueues(world.manager);

        // Missing/removed TE: no inherited color at a reused position.
        world.chunk.getTileEntityMap().remove(POS);
        world.manager.contextualLight().tileEntityChanged(POS);
        if(rgb)world.manager.rgbContextualLight().request(8,64,8);
        world.manager.publishContextualLight();
        assertWorker(world,block.getDefaultState(),0,0,rgb); clearQueues(world.manager);
        world.manager.unregisterChunk(0,0);
        MultipartFixtures.parts(tile,glow); world.chunk.getTileEntityMap().put(POS,tile);
        world.manager.registerChunk(world.chunk); clearQueues(world.manager);
        tile.onChunkLoad();
        check(!queue(world.manager,"blockQueue").hasWork(),"Load queued premature BFS");
        world.manager.publishContextualLight();
        assertWorker(world,block.getDefaultState(),15,rgb?BlockLightColors.emission(Blocks.GLOWSTONE.getDefaultState(),15):0,rgb);
        // A mixed chunk must poll only its unknown source, even when its RGB
        // polling subset splits from and later rejoins the dynamic coordinate set.
        var otherPos=new BlockPos(1,64,1);
        var other=new TileMultipart();other.setWorld(world);other.setPos(otherPos);
        MultipartFixtures.parts(other,new MultipartFixtures.UnknownPart(5));
        world.chunk.getBlockStorageArray()[4].set(1,0,1,block.getDefaultState());
        world.chunk.getTileEntityMap().put(otherPos,other);
        world.checkLight(otherPos);world.manager.publishContextualLight();clearQueues(world.manager);
        reads=world.reads;
        for(int i=0;i<10;i++)world.manager.publishContextualLight();
        check(world.reads-reads==(rgb?20:10),"Mixed chunk polled its constant material");
        MultipartFixtures.parts(other,MultipartFixtures.part("pulsar:glowstone"));other.notifyPartChange(null);
        world.manager.publishContextualLight();clearQueues(world.manager);
        reads=world.reads;
        for(int i=0;i<10;i++)world.manager.publishContextualLight();
        check(world.reads==reads,"Polling subset did not empty after part replacement");
        LightSaveProbe.verify(world,rgb);
        if(rgb)RgbFailureProbe.verify(world);
        world.manager.shutdown();
        System.out.println("MULTIPART_PASS mode="+System.getProperty("pulsar.multipartMode")+" stationaryTicks=100 tileReads=0 baselineTileReads="+baselineReads);
    }
    private static void assertWorker(ProbeWorld world,IBlockState state,int scalar,int rgb,boolean colored) throws Exception {
        int before=world.reads;
        try(var worker=Executors.newSingleThreadExecutor()) {
            worker.submit(()->{
                int info=LightInfo.resolveContextual(LightInfo.of(state),state,world,new BlockPos.MutableBlockPos(),8,64,8);
                check(LightInfo.emission(info)==scalar,"Scalar mismatch");
                if(colored)check((RgbContextualLightManager.properties(world,state,8,64,8)&0xFFF)==rgb,"RGB mismatch");
                world.propagation.update(scalar,rgb);
            }).get();
        }
        check(before==world.reads,"Worker read TileEntity");
    }
    private static LightQueue queue(WorldLightManager manager,String name) throws Exception {
        var field=WorldLightManager.class.getDeclaredField(name);field.setAccessible(true);return (LightQueue)field.get(manager);
    }
    private static void clearQueues(WorldLightManager manager) throws Exception {
        for(String name: new String[]{"skyQueue","blockQueue"}) {
            LightQueue queue=queue(manager,name);ChunkTasks task;
            while((task=queue.claimChunk(0))!=null)queue.completeTask(task);
        }
    }
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
    private static void set(Class<?> type,Object target,String name,Object value) throws Exception {
        var field=type.getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
    private static <T>T inert(Class<T> type) throws Exception {
        var unsafe=Class.forName("sun.misc.Unsafe");var field=unsafe.getDeclaredField("theUnsafe");field.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance",Class.class).invoke(field.get(null),type));
    }
}
