/*
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.light.engine.MultipartPropagation;
import com.sumirelabs.pulsar.light.engine.PulsarEngine;
import com.sumirelabs.pulsar.rgb.*;
import com.sumirelabs.pulsar.util.WorldHeightContext;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.event.world.ChunkDataEvent;

/** Light-only writes on an otherwise untouched neighbour must survive save/reload. */
final class LightSaveProbe {

    static void verify(MultipartLightProbe.ProbeWorld world, boolean rgb) throws Exception {
        var neighbour = new Chunk(world, 1, 0);
        world.manager.registerChunk(neighbour);
        var light = (PulsarChunk) neighbour;
        light.pulsar$setLightReady(true);
        check(!neighbour.needsSaving(false), "New neighbour is unexpectedly dirty");
        MultipartPropagation.publishNeighbour(world, neighbour, 14);
        check(neighbour.needsSaving(false), "Light-only neighbour not scheduled for save");
        if (rgb) {
            var height = WorldHeightContext.VANILLA;
            var storage = new RgbStorage(height.getMinLightSection(), height.getTotalLightSections());
            storage.channels[0][5].setNonNull();
            storage.channels[0][5].set(0, 0, 0, 14);
            storage.channels[0][5].updateVisible();
            ((RgbChunk) neighbour).pulsar$setRgbSnapshot(RgbSettings.projectsGameplayLight()
                ? storage.maximumSnapshot(1, false, null)
                : storage.snapshot(1, light.pulsar$getBlockNibbles()));
            ((RgbChunk) neighbour).pulsar$setRgbSkySnapshot(
                new RgbSnapshot(height.getMinLightSection(), 1, new byte[3][height.getTotalLightSections()][]));
        }
        var serializer = new LightDataSerializer();
        var saved = new NBTTagCompound();
        serializer.onChunkSave(new ChunkDataEvent.Save(neighbour, saved));
        check(saved.hasKey("PulsarLight"), "No saved light tag");
        check(!neighbour.needsSaving(false), "Saved generation remains dirty");
        var restored = new Chunk(world, 1, 0);
        serializer.onChunkLoad(new ChunkDataEvent.Load(restored, saved));
        check(((PulsarChunk) restored).pulsar$hasSavedLightValid(), "Saved light rejected");
        check(((PulsarChunk) restored).pulsar$getBlockNibbles()[5].getVisible(0, 0, 0) == 14,
            "Light did not round-trip");
        // Vanilla resets its own flag after serialization. A later publication
        // must remain pending independently of that flag.
        MultipartPropagation.publishNeighbour(world, neighbour, 8);
        var modified = Chunk.class.getDeclaredField("dirty");
        modified.setAccessible(true);
        modified.setBoolean(neighbour, false);
        check(neighbour.needsSaving(false), "Post-save publication was lost");
        world.manager.unregisterChunk(1, 0);
        verifyRestoredBoundary(world, serializer, rgb);
    }

    private static void verifyRestoredBoundary(MultipartLightProbe.ProbeWorld world, LightDataSerializer serializer,
        boolean rgb) {
        var receiver = new Chunk(world, 5, 4);
        var light = (PulsarChunk) receiver;
        light.pulsar$setLightReady(true);
        var height = WorldHeightContext.VANILLA;
        int section = height.getLightSectionIndex(4);
        var plane = light.pulsar$getBlockNibbles()[section];
        plane.set(0, 0, 8, 14);
        plane.setHidden();
        plane.updateVisible();
        if (rgb) {
            var colored = (RgbChunk) receiver;
            var storage = new RgbStorage(height.getMinLightSection(), height.getTotalLightSections());
            storage.channels[2][section].set(0, 0, 8, 14);
            storage.channels[2][section].updateVisible();
            colored.pulsar$setRgbSnapshot(storage.snapshot(1, light.pulsar$getBlockNibbles()));
            colored.pulsar$setRgbSkySnapshot(new RgbSnapshot(height.getMinLightSection(), 1,
                new byte[3][height.getTotalLightSections()][]));
        }
        var saved = new NBTTagCompound();
        serializer.onChunkSave(new ChunkDataEvent.Save(receiver, saved));

        var restored = new Chunk(world, 5, 4);
        var restoredLight = (PulsarChunk) restored;
        serializer.onChunkLoad(new ChunkDataEvent.Load(restored, saved));
        check(restoredLight.pulsar$hasSavedLightValid(), "Boundary fixture did not load saved light");
        check(restoredLight.pulsar$getBlockNibbles()[section].getVisible(0, 0, 8) == 14,
            "Boundary fixture did not restore its stale light");
        var neighbor = new Chunk(world, 4, 4);
        ((PulsarChunk) neighbor).pulsar$setLightReady(true);
        restoredLight.pulsar$setLightReady(true);
        world.manager.registerChunk(neighbor);
        world.manager.registerChunk(restored);
        world.manager.queueChunkLoadInit(5, 4, restored, PulsarEngine.getEmptySectionsForChunk(restored));
        for (int i = 0; i < 20 && world.manager.hasUpdates(); i++)
            world.manager.processClientRenderUpdates(); // drain the fixture's manual workers
        check(!world.manager.hasUpdates(), "Restored edge work did not finish");
        check(restoredLight.pulsar$getBlockNibbles()[section].getVisible(0, 0, 8) == 0,
            "Stale hidden light survived the boundary check");
        if (rgb)
            check(((RgbChunk) restored).pulsar$getRgbSnapshot().get(80, 64, 72) == 0,
                "Stale blue-only light survived the boundary check");
        world.manager.unregisterChunk(4, 4);
        world.manager.unregisterChunk(5, 4);
    }

    private static void check(boolean ok, String message) {
        if (!ok)
            throw new AssertionError(message);
    }

}
