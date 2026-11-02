package com.sumirelabs.pulsar.dynamic;

import com.sumirelabs.pulsar.api.*;
import com.sumirelabs.pulsar.light.engine.FaceOcclusion;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.*;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.*;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import java.util.*;

/** Real transformed Minecraft queries, held-item states and world-thread snapshots; no window. */
public final class DynamicLightProbe {
    private static final BlockPos POS=new BlockPos(8,72,8);
    public static class ProbeWorld extends WorldServer {
        Provider chunks;
        int renderUpdates;
        ProbeWorld(){super(null,null,null,0,null);}
        @Override public ChunkProviderServer getChunkProvider(){return chunks;}
        @Override public Chunk getChunk(int x,int z){return chunks.getLoadedChunk(x,z);}
        @Override protected boolean isChunkLoaded(int x,int z,boolean allowEmpty){return chunks.getLoadedChunk(x,z)!=null;}
        @Override public void markBlockRangeForRenderUpdate(int x,int y,int z,int xx,int yy,int zz){renderUpdates++;}
    }
    public static class Provider extends ChunkProviderServer {
        Map<Long,Chunk> loaded; Thread owner;
        Provider(){super(null,null,null);}
        @Override public Chunk getLoadedChunk(int x,int z){
            check(Thread.currentThread()==owner,"Worker read live world");
            return loaded.get((long)x<<32 | z&0xffffffffL);
        }
    }
    public static class Player extends EntityPlayerMP {
        ItemStack main,off;
        boolean burning,spectator;
        Player(){super(null,null,null,null);}
        @Override public boolean isSpectator(){return spectator;}
        @Override public boolean isEntityAlive(){return !isDead;}
        @Override public boolean isBurning(){return burning;}
        @Override public float getEyeHeight(){return 0;}
        @Override public ItemStack getHeldItemMainhand(){return main;}
        @Override public ItemStack getHeldItemOffhand(){return off;}
    }
    public static void main(String[] args)throws Exception {
        Bootstrap.register();
        set(Loader.class,Loader.instance(),"namedMods",Map.of());
        FaceOcclusion.registerDefaults();BlockLightColors.defaults();BlockLightFilters.defaults();BlockLightColors.freeze();
        ProbeWorld world=inert(ProbeWorld.class);
        set(World.class,world,"provider",new WorldProviderSurface());
        set(World.class,world,"worldInfo",new net.minecraft.world.storage.WorldInfo(new net.minecraft.nbt.NBTTagCompound()));
        set(World.class,world,"playerEntities",new ArrayList<>());
        set(World.class,world,"loadedEntityList",new ArrayList<>());
        world.chunks=inert(Provider.class);world.chunks.loaded=new HashMap<>();world.chunks.owner=Thread.currentThread();
        for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++) {
            var chunk=new Chunk(world,x,z);world.chunks.loaded.put((long)x<<32|z&0xffffffffL,chunk);
            chunk.getBlockStorageArray()[4]=new ExtendedBlockStorage(64,true);
        }
        int torch=DynamicLightGeometry.itemEmission(world,POS,new ItemStack(Blocks.TORCH),true);
        check((torch>>>12)==14,"Torch source level changed");
        check((DynamicLightGeometry.itemEmission(world,POS,new ItemStack(Items.LAVA_BUCKET),false)>>>12)==15,"Lava bucket source missing");
        check(DynamicLightGeometry.itemEmission(world,POS,new ItemStack(Blocks.REDSTONE_LAMP),true)==0,"Unlit lamp emits");
        check(DynamicLightGeometry.itemEmission(world,POS,ItemStack.EMPTY,true)==0,"Empty hand emits");
        var snapshot=DynamicLightGeometry.capture(world,POS,0xFFFF,true);
        var field=java.util.concurrent.CompletableFuture.supplyAsync(snapshot::propagate).get();
        check(field.get(9,72,8)==0xDDDD,"Radial air attenuation");
        // A closed slab face must not pass light straight through into the air below.
        put(world,POS.down(),Blocks.WOODEN_SLAB.getDefaultState().withProperty(BlockSlab.HALF,BlockSlab.EnumBlockHalf.TOP));
        var slab=DynamicLightGeometry.capture(world,POS,0xFFFF,false).propagate();
        check(DynamicLightField.peak(slab.get(8,71,8))<=12,"Closed slab entry leaked instead of taking the longer open-face path");
        put(world,POS.down(),Blocks.AIR.getDefaultState());
        // Seal a corridor so the red filter cannot be bypassed through adjacent air.
        for(int x=5;x<=12;x++)for(int y=70;y<=74;y++)for(int z=6;z<=10;z++)
            if(y!=72||z!=8)put(world,new BlockPos(x,y,z),Blocks.STONE.getDefaultState());
        put(world,POS.east(),Blocks.STAINED_GLASS.getStateFromMeta(net.minecraft.item.EnumDyeColor.RED.getMetadata()));
        var filtered=DynamicLightGeometry.capture(world,POS,0xFFFF,true).propagate();
        check((filtered.get(10,72,8)&0xFF)==0,"Red glass restored absorbed channels");
        check((filtered.get(10,72,8)&0xF00)!=0,"Red glass lost transmitted red");
        put(world,POS.east(),Blocks.AIR.getDefaultState());
        Player player=inert(Player.class);player.world=world;player.posX=8;player.posY=72;player.posZ=8;
        player.main=new ItemStack(Blocks.TORCH);player.off=ItemStack.EMPTY;world.playerEntities.add(player);
        DynamicLights manager=newManager();
        var tick=new TickEvent.WorldTickEvent(Side.SERVER,TickEvent.Phase.END,world);
        set(DynamicLights.class,null,"mode",DynamicLights.Mode.VISUAL);
        for(int i=0;i<5;i++){manager.tick(tick);Thread.sleep(10);}
        check(world.getLightFor(EnumSkyBlock.BLOCK,POS)==0,"VISUAL affected gameplay");
        set(DynamicLights.class,null,"mode",DynamicLights.Mode.GAMEPLAY);
        check(world.getLightFor(EnumSkyBlock.BLOCK,POS)==14,"GAMEPLAY missing block query");
        check(world.getLight(POS,true)>=14,"GAMEPLAY missing combined query");
        check(world.getLightFromNeighbors(POS)>=14,"GAMEPLAY missing neighbor query");
        check(world.chunks.getLoadedChunk(0,0).getBlockStorageArray()[4].getBlockLight().get(8,8,8)==0,"Dynamic light polluted saved storage");
        player.main=ItemStack.EMPTY;player.off=new ItemStack(Blocks.GLOWSTONE);
        for(int i=0;i<5;i++){manager.tick(tick);Thread.sleep(10);}
        check(world.getLightFor(EnumSkyBlock.BLOCK,POS)==15,"Offhand was ignored");
        player.off=ItemStack.EMPTY;manager.tick(tick);
        check(world.getLightFor(EnumSkyBlock.BLOCK,POS)==0,"Last source left a ghost");
        player.main=new ItemStack(Blocks.TORCH);manager.tick(tick);world.playerEntities.clear();manager.tick(tick);
        Thread.sleep(20);manager.tick(tick);
        check(world.getLightFor(EnumSkyBlock.BLOCK,POS)==0,"Late worker resurrected logged-out source");
        verifyDroppedItems(world,manager,tick,player);
        verifyBurningEntities(world,manager,tick,player);
        verifyContinuousMovement(world,manager,tick,player);
        // Real client query hooks, including the vanilla mesh snapshot cache.
        set(World.class,world,"isRemote",true);set(DynamicLightClient.class,null,"owner",world);
        set(DynamicLightClient.class,null,"fields",Map.of(1,field));
        check(world.getLightFor(EnumSkyBlock.BLOCK,POS)==0,"Client overlay mutated raw light");
        check((world.getCombinedLight(POS,0)>>>4&15)==15,"World render light missing");
        var cache=new ChunkCache(world,POS.add(-1,-1,-1),POS.add(1,1,1),1);
        check((cache.getCombinedLight(POS,0)>>>4&15)==15,"ChunkCache render light missing");
        set(DynamicLightClient.class,null,"fields",Map.of());
        check((cache.getCombinedLight(POS,0)>>>4&15)==0,"Renderer retained removed field");
        Object connection=new Object();set(DynamicLightClient.class,null,"connection",connection);
        set(DynamicLightClient.class,null,"session",42L);set(DynamicLightClient.class,null,"sequence",0L);
        var packet=new DynamicLightPacket();packet.mode=DynamicLights.Mode.VISUAL;packet.session=42;packet.revision=1;
        packet.dimension=world.provider.getDimension();packet.source=5;packet.field=field;
        DynamicLightClient.apply(packet,connection,world,connection);
        check(DynamicLightClient.light(world,8,72,8)==15,"Client did not install authoritative field");
        packet.session=41;packet.revision=10;packet.field=null;
        DynamicLightClient.apply(packet,connection,world,connection);
        check(DynamicLightClient.light(world,8,72,8)==15,"Stale world session removed light");
        packet.session=42;packet.revision=2;
        DynamicLightClient.apply(packet,new Object(),world,connection);
        check(DynamicLightClient.light(world,8,72,8)==15,"Stale connection removed light");
        DynamicLightClient.apply(packet,connection,world,connection);
        check(DynamicLightClient.light(world,8,72,8)==0,"Tombstone failed");
        packet.revision=1;packet.field=field;
        DynamicLightClient.apply(packet,connection,world,connection);
        check(DynamicLightClient.light(world,8,72,8)==0,"Old field resurrected after tombstone");
        packet.revision=3;packet.field=field;DynamicLightClient.apply(packet,connection,world,connection);
        world.renderUpdates=0;
        packet.revision=4;packet.field=new DynamicLightField(9,72,8,0,new short[]{(short)0xFFFF});
        DynamicLightClient.apply(packet,connection,world,connection);
        check(world.renderUpdates==1,"Ordinary movement duplicated render invalidation");
        world.renderUpdates=0;
        packet.revision=5;packet.field=new DynamicLightField(1000,72,8,0,new short[]{(short)0xFFFF});
        DynamicLightClient.apply(packet,connection,world,connection);
        check(world.renderUpdates==2,"Teleport rebuilt the entire gap between fields");
        verifyMeshScope(world);
        DynamicLightClient.reset();
        System.out.println("DYNAMIC_LIGHT_PASS");
    }
    private static void verifyDroppedItems(ProbeWorld world, DynamicLights manager, TickEvent.WorldTickEvent tick, Player player)throws Exception {
        BlockPos pos=new BlockPos(20,72,8);
        EntityItem torch=new EntityItem(world,20.5,72,8.5,new ItemStack(Blocks.TORCH,64));
        torch.setEntityId(300);world.loadedEntityList.add(torch);
        awaitLight(world,manager,tick,pos,14);
        int colored=DynamicLightGeometry.itemEmission(world,pos,torch.getItem(),true);
        check((colored&0xFFF)==BlockLightColors.emission(Blocks.TORCH.getDefaultState(),14),"Dropped source lost palette hue");
        check((DynamicLightGeometry.capture(world,pos,colored,true).propagate().get(20,72,8)&0xFFF)==(colored&0xFFF),"Dropped RGB source changed brightness");
        torch.getItem().setCount(1);manager.tick(tick);
        check(world.getLightFor(EnumSkyBlock.BLOCK,pos)==14,"Stack count changed brightness");
        set(DynamicLights.class,null,"mode",DynamicLights.Mode.VISUAL);
        manager.tick(tick);check(world.getLightFor(EnumSkyBlock.BLOCK,pos)==0,"Dropped VISUAL light affected gameplay");
        set(DynamicLights.class,null,"mode",DynamicLights.Mode.OFF);
        manager.tick(tick);check(world.getLightFor(EnumSkyBlock.BLOCK,pos)==0,"Dropped OFF light affected gameplay");
        set(DynamicLights.class,null,"mode",DynamicLights.Mode.GAMEPLAY);
        torch.setPosition(24.5,72,8.5);
        awaitLight(world,manager,tick,new BlockPos(24,72,8),14);
        check(world.getLightFor(EnumSkyBlock.BLOCK,pos)==6,"Moving drop retained its old source");
        torch.setPosition(20.5,72,8.5);awaitLight(world,manager,tick,pos,14);
        EntityItem glow=new EntityItem(world,20.5,72,8.5,new ItemStack(Blocks.GLOWSTONE));
        glow.setEntityId(301);world.loadedEntityList.add(glow);awaitLight(world,manager,tick,pos,15);
        glow.setDead();manager.tick(tick);
        check(world.getLightFor(EnumSkyBlock.BLOCK,pos)==14,"Removing overlapping drop removed surviving light");
        torch.setItem(new ItemStack(Blocks.STONE));manager.tick(tick);
        check(world.getLightFor(EnumSkyBlock.BLOCK,pos)==0,"Non-emitting replacement retained light");
        torch.setItem(new ItemStack(Items.LAVA_BUCKET));awaitLight(world,manager,tick,pos,15);
        // Chunk unload can precede removal from loadedEntityList. It must extinguish the source already.
        Chunk removed=world.chunks.loaded.remove(1L<<32);manager.tick(tick);
        check(DynamicLights.gameplay(world,20,72,8)==0,"Unloaded drop retained light");
        world.chunks.loaded.put(1L<<32,removed);awaitLight(world,manager,tick,pos,15);
        torch.setItem(ItemStack.EMPTY);manager.tick(tick);
        check(DynamicLights.gameplay(world,20,72,8)==0,"Empty dropped stack retained light");
        // Pickup transfers the light to a hand; death/despawn/merge share the same live-entity removal path.
        player.posX=20.5;player.posY=72;player.posZ=8.5;player.main=new ItemStack(Blocks.TORCH);
        world.playerEntities.add(player);torch.setDead();awaitLight(world,manager,tick,pos,14);
        world.playerEntities.clear();manager.tick(tick);
        check(DynamicLights.gameplay(world,20,72,8)==0,"Pickup transfer left a duplicate source");
        world.loadedEntityList.clear();
        EntityItem pending=new EntityItem(world,20.5,72,8.5,new ItemStack(Blocks.GLOWSTONE));
        pending.setEntityId(302);world.loadedEntityList.add(pending);manager.tick(tick);
        world.loadedEntityList.clear();manager.tick(tick);Thread.sleep(20);manager.tick(tick);
        check(DynamicLights.gameplay(world,20,72,8)==0,"Late worker resurrected removed drop");
    }
    private static void awaitLight(ProbeWorld world,DynamicLights manager,TickEvent.WorldTickEvent tick,BlockPos pos,int expected)throws Exception {
        long deadline=System.nanoTime()+2_000_000_000L;
        do {
            manager.tick(tick);
            if(world.getLightFor(EnumSkyBlock.BLOCK,pos)==expected)return;
            Thread.sleep(5);
        } while(System.nanoTime()<deadline);
        throw new AssertionError("Dynamic light at "+pos+": expected "+expected+", got "+world.getLightFor(EnumSkyBlock.BLOCK,pos));
    }
    private static void verifyBurningEntities(ProbeWorld world,DynamicLights manager,TickEvent.WorldTickEvent tick,Player player)throws Exception {
        BlockPos pos=new BlockPos(20,72,8);
        var mob=new net.minecraft.entity.monster.EntityZombie(world);
        mob.setEntityId(400);mob.setPosition(20.5,72,8.5);world.loadedEntityList.add(mob);
        manager.tick(tick);check(DynamicLights.gameplay(world,20,72,8)==0,"Unlit mob emitted light");
        mob.setFire(30);awaitLight(world,manager,tick,pos,15);
        int fire=DynamicLightGeometry.fireEmission(world,pos,true);
        check((fire&0xFFF)==BlockLightColors.emission(Blocks.FIRE.getDefaultState(),15),"Burning light lost fire palette");
        mob.setPosition(24.5,72,8.5);awaitLight(world,manager,tick,new BlockPos(24,72,8),15);
        check(DynamicLights.gameplay(world,20,72,8)==7,"Moving fire retained old position");
        mob.extinguish();manager.tick(tick);
        check(DynamicLights.gameplay(world,24,72,8)==0,"Extinguished mob retained light");
        mob.setPosition(20.5,72,8.5);mob.setFire(30);awaitLight(world,manager,tick,pos,15);
        mob.setHealth(0);manager.tick(tick);
        check(DynamicLights.gameplay(world,20,72,8)==0,"Dying mob retained light before entity removal");
        mob.setHealth(20);awaitLight(world,manager,tick,pos,15);
        Chunk removed=world.chunks.loaded.remove(1L<<32);manager.tick(tick);
        check(DynamicLights.gameplay(world,20,72,8)==0,"Unloaded burning mob retained light");
        world.chunks.loaded.put(1L<<32,removed);awaitLight(world,manager,tick,pos,15);
        set(DynamicLights.class,null,"mode",DynamicLights.Mode.VISUAL);manager.tick(tick);
        check(DynamicLights.gameplay(world,20,72,8)==0,"VISUAL fire affected gameplay");
        check(publishedCell(world,pos)==0xFFFF,"VISUAL fire was not propagated");
        set(DynamicLights.class,null,"mode",DynamicLights.Mode.OFF);manager.tick(tick);
        check(DynamicLights.gameplay(world,20,72,8)==0,"OFF fire affected gameplay");
        set(DynamicLights.class,null,"mode",DynamicLights.Mode.GAMEPLAY);
        world.loadedEntityList.clear();manager.tick(tick);
        // Burning and held colors combine in one player source, even when the player appears in both lists.
        var palette=com.sumirelabs.pulsar.rgb.RgbPaletteOverrides.current().values;
        com.sumirelabs.pulsar.rgb.RgbPaletteOverrides.install(new com.sumirelabs.pulsar.rgb.RgbPaletteOverrides.Values(
                Map.of(Blocks.FIRE.getDefaultState(),0xF00,Blocks.GLOWSTONE.getDefaultState(),0x00F),Map.of()));
        set(com.sumirelabs.pulsar.core.RgbMixinSelection.class,null,"enabled",true);
        try {
            player.main=new ItemStack(Blocks.GLOWSTONE);player.off=ItemStack.EMPTY;player.burning=true;
            world.playerEntities.add(player);world.loadedEntityList.add(player);
            awaitCell(world,manager,tick,pos,0xFF0F);
            player.burning=false;awaitCell(world,manager,tick,pos,0xF00F);
            player.burning=true;player.main=ItemStack.EMPTY;awaitCell(world,manager,tick,pos,0xFF00);
            player.spectator=true;manager.tick(tick);check(publishedCell(world,pos)==0,"Burning spectator emitted light");
            player.spectator=false;awaitCell(world,manager,tick,pos,0xFF00);
            player.isDead=true;manager.tick(tick);check(publishedCell(world,pos)==0,"Dead player retained fire light");
            player.isDead=false;manager.tick(tick);player.burning=false;manager.tick(tick);
            Thread.sleep(20);manager.tick(tick);check(publishedCell(world,pos)==0,"Late worker restored extinguished player fire");
        } finally {
            world.playerEntities.clear();world.loadedEntityList.clear();manager.tick(tick);
            com.sumirelabs.pulsar.rgb.RgbPaletteOverrides.install(palette);
            set(com.sumirelabs.pulsar.core.RgbMixinSelection.class,null,"enabled",false);
        }
    }
    private static int publishedCell(ProbeWorld world,BlockPos pos)throws Exception {
        var worlds=DynamicLights.class.getDeclaredField("WORLDS");worlds.setAccessible(true);
        Object state=((Map<?,?>)worlds.get(null)).get(world);
        var visible=state.getClass().getDeclaredField("visible");visible.setAccessible(true);
        int value=0;
        for(Object field:(List<?>)visible.get(state))value=DynamicLightField.merge(value,((DynamicLightField)field).get(pos.getX(),pos.getY(),pos.getZ()));
        return value;
    }
    private static void verifyContinuousMovement(ProbeWorld world,DynamicLights manager,TickEvent.WorldTickEvent tick,Player player)throws Exception {
        player.isDead=false;player.burning=false;player.spectator=false;
        player.main=new ItemStack(Blocks.TORCH);player.off=ItemStack.EMPTY;
        player.posX=20.5;player.posY=72;player.posZ=8.5;world.playerEntities.add(player);
        awaitLight(world,manager,tick,new BlockPos(20,72,8),14);
        var worlds=DynamicLights.class.getDeclaredField("WORLDS");worlds.setAccessible(true);
        Object state=((Map<?,?>)worlds.get(null)).get(world);
        var sources=state.getClass().getDeclaredField("sources");sources.setAccessible(true);
        Object source=((Map<?,?>)sources.get(state)).get(player.getEntityId());
        var pending=source.getClass().getDeclaredField("pending");pending.setAccessible(true);
        var published=source.getClass().getDeclaredField("field");published.setAccessible(true);
        // Saturating the shared worker queue must retain the last valid publication.
        var workerField=DynamicLights.class.getDeclaredField("WORKER");workerField.setAccessible(true);
        var worker=(java.util.concurrent.ThreadPoolExecutor)workerField.get(null);
        var release=new java.util.concurrent.CountDownLatch(1);
        var started=new java.util.concurrent.CountDownLatch(2);
        try {
            for(int i=0;i<2;i++)worker.execute(()->{started.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            check(started.await(2,java.util.concurrent.TimeUnit.SECONDS),"Could not occupy light workers");
            while(worker.getQueue().remainingCapacity()>0)worker.execute(()->{});
            set(source.getClass(),source,"sampled",-100L);
            manager.tick(tick);
            check(DynamicLights.gameplay(world,20,72,8)==14,"Queue backpressure extinguished a valid light");
        } finally {release.countDown();}
        long drainDeadline=System.nanoTime()+2_000_000_000L;
        while(!worker.getQueue().isEmpty()&&System.nanoTime()<drainDeadline)Thread.sleep(1);
        check(worker.getQueue().isEmpty(),"Light worker queue did not drain");
        // Deterministically delay a worker while the player crosses several cell boundaries.
        var delayed=new java.util.concurrent.CompletableFuture<DynamicLightField>();
        pending.set(source,delayed);
        for(int x=21;x<=23;x++) {
            player.posX=x+.5;manager.tick(tick);
            check(!delayed.isCancelled() && pending.get(source)==delayed,"Walking cancelled in-flight light work");
            check(DynamicLights.gameplay(world,x,72,8)>0,"Walking blanked the visible field before its replacement");
        }
        var completed=DynamicLightGeometry.capture(world,new BlockPos(21,72,8),0xEEEE,false).propagate();
        delayed.complete(completed);player.posX=24.5;manager.tick(tick);
        check(published.get(source)==completed,"Movement discarded a completed worker result");
        check(DynamicLights.gameplay(world,24,72,8)>0,"Completion produced a dark frame");
        var teleportPending=new java.util.concurrent.CompletableFuture<DynamicLightField>();pending.set(source,teleportPending);
        player.posX=-20.5;manager.tick(tick);
        check(teleportPending.isCancelled(),"Teleport did not retire old work");
        check(DynamicLights.gameplay(world,21,72,8)==0,"Teleport left the old light behind");
        world.playerEntities.clear();manager.tick(tick);
    }
    private static void verifyMeshScope(ProbeWorld world)throws Exception {
        var blue=new DynamicLightField(8,72,8,0,new short[]{(short)0xF007});
        var red=new DynamicLightField(8,72,8,0,new short[]{(short)0x8800});
        set(DynamicLightClient.class,null,"fields",Map.of(1,blue));
        set(DynamicLightClient.class,null,"rgb",true);set(DynamicLightClient.class,null,"projected",false);
        try(var scope=DynamicLightClient.beginMesh()) {
            int tint=com.sumirelabs.pulsar.rgb.RgbClient.tintAbgr(0xFFFFFFFF,8.5,72.5,8.5);
            set(DynamicLightClient.class,null,"fields",Map.of(1,red));set(DynamicLightClient.class,null,"projected",true);
            check(DynamicLightClient.sample(world,8,72,8)==0xF007,"Mesh changed RGB midway");
            check(DynamicLightClient.light(world,8,72,8)==15,"Mesh changed canonical brightness midway");
            check(com.sumirelabs.pulsar.rgb.RgbClient.tintAbgr(0xFFFFFFFF,8.5,72.5,8.5)==tint,"RGB tint read another publication than scalar light");
            try(var nested=DynamicLightClient.beginMesh()) {
                check(DynamicLightClient.sample(world,8,72,8)==0xF007,"Nested renderer discarded mesh snapshot");
            }
            check(java.util.concurrent.CompletableFuture.supplyAsync(()->DynamicLightClient.sample(world,8,72,8)).get()==0x8800,"Mesh snapshot leaked to another thread");
        }
        check(DynamicLightClient.sample(world,8,72,8)==0x8800,"Mesh scope retained old light after completion");
        var renderChunk=net.minecraft.client.renderer.chunk.RenderChunk.class;
        check(Arrays.stream(renderChunk.getDeclaredMethods()).anyMatch(m->m.getName().contains("pulsar$dynamicSnapshot")),"Missing vanilla mesh snapshot wrapper");
        try { inert(renderChunk).rebuildChunk(0,0,0,null); } catch(NullPointerException expected) {}
        var local=DynamicLightClient.class.getDeclaredField("MESH_VIEW");local.setAccessible(true);
        check(((ThreadLocal<?>)local.get(null)).get()==null,"Exceptional mesh exit leaked a snapshot");
    }
    private static void awaitCell(ProbeWorld world,DynamicLights manager,TickEvent.WorldTickEvent tick,BlockPos pos,int expected)throws Exception {
        long deadline=System.nanoTime()+2_000_000_000L;
        do {manager.tick(tick);if(publishedCell(world,pos)==expected)return;Thread.sleep(5);}while(System.nanoTime()<deadline);
        throw new AssertionError("Expected dynamic cell "+Integer.toHexString(expected)+", got "+Integer.toHexString(publishedCell(world,pos)));
    }
    private static DynamicLights newManager()throws Exception {var c=DynamicLights.class.getDeclaredConstructor();c.setAccessible(true);return c.newInstance();}
    private static void put(ProbeWorld world,BlockPos pos,IBlockState state){world.chunks.getLoadedChunk(pos.getX()>>4,pos.getZ()>>4).getBlockStorageArray()[pos.getY()>>4].set(pos.getX()&15,pos.getY()&15,pos.getZ()&15,state);}
    private static void set(Class<?> type,Object instance,String name,Object value)throws Exception {var f=type.getDeclaredField(name);f.setAccessible(true);f.set(instance,value);}
    private static <T>T inert(Class<T> type)throws Exception {var u=Class.forName("sun.misc.Unsafe");var f=u.getDeclaredField("theUnsafe");f.setAccessible(true);return type.cast(u.getMethod("allocateInstance",Class.class).invoke(f.get(null),type));}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
