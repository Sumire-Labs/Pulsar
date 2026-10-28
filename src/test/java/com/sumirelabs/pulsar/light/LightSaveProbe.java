package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.light.engine.MultipartPropagation;
import com.sumirelabs.pulsar.rgb.*;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.event.world.ChunkDataEvent;

/** Light-only writes on an otherwise untouched neighbour must survive save/reload. */
final class LightSaveProbe {
    static void verify(MultipartLightProbe.ProbeWorld world, boolean rgb) throws Exception {
        var neighbour = new Chunk(world,1,0);
        world.manager.registerChunk(neighbour);
        var light=(PulsarChunk)neighbour;
        light.pulsar$setLightReady(true);
        check(!neighbour.needsSaving(false),"New neighbour is unexpectedly dirty");
        MultipartPropagation.publishNeighbour(world,neighbour,14);
        check(neighbour.needsSaving(false),"Light-only neighbour not scheduled for save");
        if(rgb) {
            var height=WorldHeightContext.VANILLA;
            var storage=new RgbStorage(height.getMinLightSection(),height.getTotalLightSections());
            storage.channels[0][5].setNonNull();storage.channels[0][5].set(0,0,0,14);storage.channels[0][5].updateVisible();
            ((RgbChunk)neighbour).pulsar$setRgbSnapshot(RgbSettings.projectsGameplayLight()
                    ? storage.maximumSnapshot(1,false,null) : storage.snapshot(1,light.pulsar$getBlockNibbles()));
            ((RgbChunk)neighbour).pulsar$setRgbSkySnapshot(new RgbSnapshot(height.getMinLightSection(),1,new byte[3][height.getTotalLightSections()][]));
        }
        var serializer=new LightDataSerializer(); var saved=new NBTTagCompound();
        serializer.onChunkSave(new ChunkDataEvent.Save(neighbour,saved));
        check(saved.hasKey("PulsarLight"),"No saved light tag");
        check(!neighbour.needsSaving(false),"Saved generation remains dirty");
        var restored=new Chunk(world,1,0);
        serializer.onChunkLoad(new ChunkDataEvent.Load(restored,saved));
        check(((PulsarChunk)restored).pulsar$hasSavedLightValid(),"Saved light rejected");
        check(((PulsarChunk)restored).pulsar$getBlockNibbles()[5].getVisible(0,0,0)==14,"Light did not round-trip");
        // Vanilla resets its own flag after serialization. A later publication
        // must remain pending independently of that flag.
        MultipartPropagation.publishNeighbour(world,neighbour,8);
        var modified=Chunk.class.getDeclaredField("dirty"); modified.setAccessible(true); modified.setBoolean(neighbour,false);
        check(neighbour.needsSaving(false),"Post-save publication was lost");
        world.manager.unregisterChunk(1,0);
    }
    private static void check(boolean ok,String message) { if(!ok)throw new AssertionError(message); }
}
