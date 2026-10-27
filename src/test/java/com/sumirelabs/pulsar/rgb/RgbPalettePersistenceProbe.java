package com.sumirelabs.pulsar.rgb;

import com.sumirelabs.pulsar.light.*;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import com.sumirelabs.pulsar.world.PulsarWorld;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.*;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.event.world.ChunkDataEvent;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Deliberate epoch changes at serialization boundaries, using the real transformed Chunk. */
public final class RgbPalettePersistenceProbe {
    private static final class ProbeWorld extends WorldServer implements PulsarWorld {
        ProbeWorld(){super(null,null,null,0,null);}
        public WorldLightManager pulsar$getLightManager(){return null;}
        public void pulsar$shutdown(){}
        public Chunk pulsar$getAnyChunkImmediately(int x,int z){return null;}
        public boolean pulsar$hasChunkPendingLight(int x,int z){return false;}
        public void pulsar$flushLightUpdates(){}
    }
    private static final class ProbeChunk extends Chunk {
        boolean changeOnSave;
        ProbeChunk(World world){super(world,0,0);}
        public SWMRNibbleArray[] pulsar$getBlockNibbles(){
            if(changeOnSave){changeOnSave=false;advance();}
            try {var field=Chunk.class.getDeclaredField("pulsar$blockNibbles");field.setAccessible(true);return (SWMRNibbleArray[])field.get(this);}
            catch(ReflectiveOperationException e){throw new AssertionError(e);}
        }
    }
    private static final class ChangingRoot extends NBTTagCompound {
        boolean armed;
        @Override public int getInteger(String name){if(armed&&name.equals("version")){armed=false;advance();}return super.getInteger(name);}
    }
    static void verify() throws Exception {
        new com.sumirelabs.pulsar.core.RgbMixinSelection().onLoad("com.sumirelabs.pulsar.mixin");
        var mods=net.minecraftforge.fml.common.Loader.class.getDeclaredField("namedMods");mods.setAccessible(true);
        if(mods.get(net.minecraftforge.fml.common.Loader.instance())==null)mods.set(net.minecraftforge.fml.common.Loader.instance(),new java.util.HashMap<>());
        var unsafe=Class.forName("sun.misc.Unsafe");var singleton=unsafe.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        World world=(World)unsafe.getMethod("allocateInstance",Class.class).invoke(singleton.get(null),ProbeWorld.class);
        var provider=World.class.getDeclaredField("provider");provider.setAccessible(true);
        provider.set(world,new WorldProvider(){public DimensionType getDimensionType(){return DimensionType.OVERWORLD;}public boolean hasSkyLight(){return false;}});
        var info=World.class.getDeclaredField("worldInfo");info.setAccessible(true);
        info.set(world,new WorldInfo(new WorldSettings(1,GameType.CREATIVE,false,false,WorldType.FLAT),"palette-probe"));
        ProbeChunk source=new ProbeChunk(world);PulsarChunk light=(PulsarChunk)(Object)source;
        int count=WorldHeightContext.VANILLA.getTotalLightSections();
        ((RgbChunk)(Object)source).pulsar$setRgbSnapshot(new RgbSnapshot(WorldHeightContext.VANILLA.getMinLightSection(),1,new byte[3][count][],new byte[count][]));
        light.pulsar$setLightReady(true);
        var serializer=new LightDataSerializer();
        var racedSave=new NBTTagCompound();source.changeOnSave=true;
        serializer.onChunkSave(new ChunkDataEvent.Save(source,racedSave));
        require(!source.changeOnSave,"Save race hook was not exercised");
        require(!racedSave.hasKey("PulsarLight"),"A save crossing palette epochs must not persist a valid light tag");
        var saved=new NBTTagCompound();serializer.onChunkSave(new ChunkDataEvent.Save(source,saved));
        require(saved.hasKey("PulsarLight"),"Stable save must retain its cache");
        ProbeChunk loaded=new ProbeChunk(world);serializer.onChunkLoad(new ChunkDataEvent.Load(loaded,saved));
        require(((PulsarChunk)(Object)loaded).pulsar$hasSavedLightValid(),"Stable cache load failed");
        advance();
        for(var method:Chunk.class.getDeclaredMethods())if(method.getName().contains("pulsar$onLoad")) {
            method.setAccessible(true);method.invoke(loaded,new CallbackInfo("onLoad",false));
        }
        require(!((PulsarChunk)(Object)loaded).pulsar$hasSavedLightValid(),"Delayed registration kept a stale saved-light epoch");
        var changing=new ChangingRoot();changing.merge(saved.getCompoundTag("PulsarLight"));changing.armed=true;
        var racedData=new NBTTagCompound();racedData.setTag("PulsarLight",changing);
        ProbeChunk racedLoad=new ProbeChunk(world);serializer.onChunkLoad(new ChunkDataEvent.Load(racedLoad,racedData));
        require(!changing.armed,"Load race hook was not exercised");
        require(!((PulsarChunk)(Object)racedLoad).pulsar$hasSavedLightValid(),"A load crossing palette epochs must be relit");
        System.out.println("RGB_PALETTE_PERSISTENCE_PASS");
    }
    private static void advance(){RgbPaletteOverrides.install(RgbPaletteOverrides.current().values);}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
