package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.light.engine.LightInfo;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import com.sumirelabs.pulsar.world.PulsarWorld;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Executes the released mods' real light getters; synthetic world/state avoids mod bootstrap. */
public final class IndustrialLightProbe {
    private static final BlockPos POS=new BlockPos(1,64,1);
    private static class ProbeWorld extends WorldServer implements PulsarWorld {
        WorldLightManager manager;SampleChunk chunk;Thread owner;int reads;
        ProbeWorld(){super(null,null,null,0,null);}
        public TileEntity getTileEntity(BlockPos pos) {
            if(Thread.currentThread()!=owner)throw new AssertionError("Mod light getter ran on worker");
            reads++;return chunk.getTileEntityMap().get(pos);
        }
        public WorldLightManager pulsar$getLightManager(){return manager;}
        public void pulsar$shutdown(){}
        public Chunk pulsar$getAnyChunkImmediately(int x,int z){return chunk;}
        public boolean pulsar$hasChunkPendingLight(int x,int z){return false;}
        public void pulsar$flushLightUpdates(){}
    }
    private static class SampleChunk extends Chunk {
        IBlockState state;
        SampleChunk(){super(null,0,0);getBlockStorageArray()[4]=new ExtendedBlockStorage(64,true){public boolean isEmpty(){return false;}};}
        public IBlockState getBlockState(int x,int y,int z){return x==1&&y==64&&z==1?state:Blocks.AIR.getDefaultState();}
    }
    public static void main(String[] args)throws Exception {
        Bootstrap.register();
        var mods=net.minecraftforge.fml.common.Loader.class.getDeclaredField("namedMods");mods.setAccessible(true);
        mods.set(net.minecraftforge.fml.common.Loader.instance(),Map.of());
        if(args[0].equals("enderio")) {
            for(String name:new String[]{"BlockElectricLight","BlockLightNode"}) {
                var active=new AtomicBoolean();
                verify((Block)inert(Class.forName("crazypants.enderio.machines.machine.light."+name)),null,
                        ()->active.set(true),()->active.set(false),15,active);
            }
        } else {
            boolean classic=args[0].equals("classic");
            Class<?> tileType=Class.forName(classic?"ic2.core.block.crop.TileEntityCrop":"ic2.core.crop.TileEntityCrop");
            Object tile=inert(tileType);
            Object crop=Class.forName(classic?"ic2.core.block.crop.crops.CropRedWheat":"ic2.core.crop.cropcard.CropRedWheat").getConstructor().newInstance();
            set(tileType,tile,classic?"card":"crop",crop);
            var size=tileType.getDeclaredField(classic?"size":"currentSize");size.setAccessible(true);
            var block=(Block)inert(Class.forName(classic?"ic2.core.block.crop.BlockCrop":"ic2.core.block.BlockTileEntity"));
            Runnable on=()->{try{if(classic)size.setInt(tile,7);else size.setByte(tile,(byte)7);}catch(Exception e){throw new AssertionError(e);}};
            Runnable off=()->{try{if(classic)size.setInt(tile,6);else size.setByte(tile,(byte)6);}catch(Exception e){throw new AssertionError(e);}};
            verify(block,(TileEntity)tile,on,off,7,new AtomicBoolean());
        }
        System.out.println("INDUSTRIAL_PASS "+args[0]);
    }
    private static void verify(Block block,TileEntity tile,Runnable on,Runnable off,int level,AtomicBoolean active)throws Exception {
        var world=inert(ProbeWorld.class);world.owner=Thread.currentThread();world.chunk=new SampleChunk();
        if(tile!=null)world.chunk.getTileEntityMap().put(POS,tile);
        var state=(IBlockState)Proxy.newProxyInstance(IndustrialLightProbe.class.getClassLoader(),new Class[]{IBlockState.class},(p,m,a)->switch(m.getName()) {
            case "getBlock" -> block;
            case "getLightValue" -> a==null||a.length==0?0:block.getLightValue((IBlockState)p,(IBlockAccess)a[0],(BlockPos)a[1]);
            case "getLightOpacity" -> 0;
            case "getValue" -> active.get();
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p==a[0];
            case "toString" -> block.getClass().getName();
            default -> throw new AssertionError(m);
        });
        world.chunk.state=state;
        var samples=new ContextualLightManager(world,WorldHeightContext.VANILLA);
        world.manager=inert(WorldLightManager.class);set(WorldLightManager.class,world.manager,"contextualLight",samples);
        off.run();samples.load(world.chunk);
        check(LightInfo.hasContextualValues(LightInfo.of(state)),"Getter was not recognized as contextual");
        for(int expected:new int[]{0,level,0,level}) {
            if(expected==0)off.run();else on.run();
            samples.request(1,64,1);samples.flushSamples((x,y,z,lanes)->{});
            int before=world.reads;
            try(var worker=Executors.newSingleThreadExecutor()) {
                int actual=worker.submit(()->LightInfo.emission(LightInfo.resolveContextual(LightInfo.of(state),state,world,new BlockPos.MutableBlockPos(),1,64,1))).get();
                check(actual==expected,"Emission mismatch for "+block.getClass().getName()+": "+actual+" != "+expected);
            }
            check(before==world.reads,"Worker read mod tile");
        }
        samples.unload(0,0);off.run();samples.load(world.chunk);
        check(LightInfo.emission(samples.read(LightInfo.of(state),state,1,64,1))==0,"Reload retained stale emission");
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static void set(Class<?> type,Object owner,String name,Object value)throws Exception {var f=type.getDeclaredField(name);f.setAccessible(true);f.set(owner,value);}
    @SuppressWarnings("unchecked") private static <T>T inert(Class<T> type)throws Exception {
        var u=Class.forName("sun.misc.Unsafe");var f=u.getDeclaredField("theUnsafe");f.setAccessible(true);
        return (T)u.getMethod("allocateInstance",Class.class).invoke(f.get(null),type);
    }
}
