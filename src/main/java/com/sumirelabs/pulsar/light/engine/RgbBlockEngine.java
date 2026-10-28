package com.sumirelabs.pulsar.light.engine;

import com.sumirelabs.pulsar.light.ChunkTasks;
import com.sumirelabs.pulsar.rgb.*;
import com.sumirelabs.pulsar.util.CoordinateUtils;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import com.sumirelabs.pulsar.api.ExtendedWorld;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import java.util.*;
import java.util.function.LongPredicate;

/** Packed RGB block lane with selectable independent or RGB-maximum gameplay light.
 * All components execute under one scheduler reservation; snapshots publish only after all finish.
 */
public final class RgbBlockEngine extends ScalarBlockEngine {
    private final Set<Chunk> touched = Collections.newSetFromMap(new IdentityHashMap<>());
    private LongPredicate publicationCurrent;
    private int colorPositions, colorIncrease, colorDecrease;
    public int colorPositions(){return colorPositions;}
    public int colorIncrease(){return colorIncrease;}
    public int colorDecrease(){return colorDecrease;}
    private final PulsarEngine[] channels;
    public RgbBlockEngine(World world, WorldHeightContext height) {
        super(world, height);
        if (Boolean.parseBoolean(System.getProperty("pulsar.rgb.packed", "true"))) channels = new PulsarEngine[]{new RgbPackedBlockChannelEngine(world, height, touched)};
        else {
            channels = new PulsarEngine[3];
            for (int c = 0; c < 3; c++) channels[c] = new RgbChannelEngine(world, height, c, touched);
        }
    }
    public void processColors(ChunkTasks task) {
        touched.clear();
        colorPositions=colorIncrease=colorDecrease=0;
        publicationCurrent = null;
        int x = CoordinateUtils.getChunkX(task.chunkCoordinate), z = CoordinateUtils.getChunkZ(task.chunkCoordinate);
        try {
            for (PulsarEngine engine : channels) {
                if (task.loadInitChunk != null && task.initialLightChunk == null) {
                    engine.loadInChunk(task.loadInitChunk, task.loadInitEmptySections);
                    check(engine);
                }
                if (task.initialLightChunk != null) {
                    engine.light(task.initialLightChunk, getEmptySectionsForChunk(task.initialLightChunk), false);
                    check(engine);
                } else if (task.changedSectionSet != null || (task.changedPositions != null && !task.changedPositions.isEmpty())) {
                    engine.blocksChangedInChunk(x, z, task.changedPositions, task.changedSectionSet);
                    check(engine);
                }
                if (task.queuedEdgeChecksBlock != null) {
                    engine.checkChunkEdges(x, z, task.queuedEdgeChecksBlock);
                    check(engine);
                }
            }
        } catch (Throwable error) { touched.clear(); throw error; }
        finally { finishPrivatePlanes(); }
    }
    public static final class QueueOverflow extends RuntimeException {}
    private void check(PulsarEngine engine) {
        if (engine.wasQueueOverflowed()) throw new QueueOverflow();
        colorPositions+=engine.lastPositionsProcessed; colorIncrease+=engine.lastBfsIncreaseTotal; colorDecrease+=engine.lastBfsDecreaseTotal;
        engine.lastPositionsProcessed=engine.lastBfsIncreaseTotal=engine.lastBfsDecreaseTotal=0;
    }
    public void relightColors(Map<Long, Chunk> targets, Map<Long, Chunk> available, LongPredicate current) {
        touched.clear();
        colorPositions=colorIncrease=colorDecrease=0;
        publicationCurrent = current;
        try {
            for (PulsarEngine engine : channels) {
                engine.relightChunks(targets, available, current);
                check(engine);
            }
        } catch (Throwable error) { touched.clear(); throw error; }
        finally { finishPrivatePlanes(); }
    }
    private void finishPrivatePlanes() {
        for (PulsarEngine engine : channels) if (engine instanceof PackedRgbOwner packed) packed.finishRgbTask();
    }
    public boolean requiresScalarLighting(){return !RgbSettings.projectsGameplayLight();}
    public void publishColors() {
        try { publish(); } finally { touched.clear(); publicationCurrent = null; }
    }
    @Override
    protected void onNibbleVisible(int cacheIndex, com.sumirelabs.pulsar.light.SWMRNibbleArray nibble) {
        super.onNibbleVisible(cacheIndex, nibble);
        // Filters can leave every RGB component unchanged while gameplay light
        // changes in a neighboring chunk. That canonical plane must publish too.
        Chunk chunk = this.chunkCache[cacheIndex % 25];
        if (chunk != null) touched.add(chunk);
    }
    private void publish() {
        for (Chunk chunk : touched) synchronized (chunk) {
            if (publicationCurrent != null && !publicationCurrent.test(CoordinateUtils.getChunkKey(chunk.x, chunk.z))) continue;
            if (((ExtendedWorld) world).pulsar$getAnyChunkImmediately(chunk.x, chunk.z) != chunk) continue;
            RgbChunk rgb = (RgbChunk) chunk;
            RgbSnapshot previous = rgb.pulsar$getRgbSnapshot();
            RgbStorage storage = rgb.pulsar$getRgbStorage();
            RgbSnapshot next = RgbSettings.projectsGameplayLight()
                    ? storage.maximumSnapshot(previous == null ? 1 : previous.revision + 1, false, previous)
                    : storage.snapshot(previous == null ? 1 : previous.revision + 1,
                    ((com.sumirelabs.pulsar.light.PulsarChunk) chunk).pulsar$getBlockNibbles(), previous);
            if (RgbSettings.projectsGameplayLight()) RgbCanonicalLight.install(chunk, next, false, storage.empty[0], heightContext);
            if (previous == null || !previous.sameLightData(next)) {
                ((com.sumirelabs.pulsar.light.PulsarChunk)chunk).pulsar$markLightDirty();
                rgb.pulsar$setRgbSnapshot(next);
                RgbNetwork.changed(chunk, previous == null || !previous.sameCanonical(next));
            }
        }
    }
}
