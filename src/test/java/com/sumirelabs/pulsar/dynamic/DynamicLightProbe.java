package com.sumirelabs.pulsar.dynamic;

import com.sumirelabs.pulsar.api.*;
import com.sumirelabs.pulsar.light.engine.FaceOcclusion;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayerMP;
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
        ProbeWorld(){super(null,null,null,0,null);}
        @Override public ChunkProviderServer getChunkProvider(){return chunks;}
        @Override public Chunk getChunk(int x,int z){return chunks.getLoadedChunk(x,z);}
        @Override protected boolean isChunkLoaded(int x,int z,boolean allowEmpty){return chunks.getLoadedChunk(x,z)!=null;}
        @Override public void markBlockRangeForRenderUpdate(int x,int y,int z,int xx,int yy,int zz){}
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
        Player(){super(null,null,null,null);}
        @Override public boolean isSpectator(){return false;}
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
        world.chunks=inert(Provider.class);world.chunks.loaded=new HashMap<>();world.chunks.owner=Thread.currentThread();
        for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++) {
            var chunk=new Chunk(world,x,z);world.chunks.loaded.put((long)x<<32|z&0xffffffffL,chunk);
            chunk.getBlockStorageArray()[4]=new ExtendedBlockStorage(64,true);
        }
        int torch=DynamicLightGeometry.held(world,POS,new ItemStack(Blocks.TORCH),true);
        check((torch>>>12)==14,"Torch source level changed");
        check((DynamicLightGeometry.held(world,POS,new ItemStack(Items.LAVA_BUCKET),false)>>>12)==15,"Lava bucket source missing");
        check(DynamicLightGeometry.held(world,POS,new ItemStack(Blocks.REDSTONE_LAMP),true)==0,"Unlit lamp emits");
        check(DynamicLightGeometry.held(world,POS,ItemStack.EMPTY,true)==0,"Empty hand emits");
        var snapshot=DynamicLightGeometry.capture(world,POS,0xFFFF,true);
        var field=java.util.concurrent.CompletableFuture.supplyAsync(snapshot::propagate).get();
        check(field.get(9,72,8)==0xEEEE,"Air attenuation");
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
        DynamicLightClient.reset();
        System.out.println("DYNAMIC_LIGHT_PASS");
    }
    private static DynamicLights newManager()throws Exception {var c=DynamicLights.class.getDeclaredConstructor();c.setAccessible(true);return c.newInstance();}
    private static void put(ProbeWorld world,BlockPos pos,IBlockState state){world.chunks.getLoadedChunk(pos.getX()>>4,pos.getZ()>>4).getBlockStorageArray()[pos.getY()>>4].set(pos.getX()&15,pos.getY()&15,pos.getZ()&15,state);}
    private static void set(Class<?> type,Object instance,String name,Object value)throws Exception {var f=type.getDeclaredField(name);f.setAccessible(true);f.set(instance,value);}
    private static <T>T inert(Class<T> type)throws Exception {var u=Class.forName("sun.misc.Unsafe");var f=u.getDeclaredField("theUnsafe");f.setAccessible(true);return type.cast(u.getMethod("allocateInstance",Class.class).invoke(f.get(null),type));}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
