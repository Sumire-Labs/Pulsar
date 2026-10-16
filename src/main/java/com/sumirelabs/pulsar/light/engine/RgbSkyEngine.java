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

/** Prototype RGB sky lane: three existing BFS propagators plus independent gameplay skylight.
 * All components execute under one scheduler reservation; snapshots publish only after all finish.
 */
public final class RgbSkyEngine extends ScalarSkyEngine {
    private final Set<Chunk> touched = Collections.newSetFromMap(new IdentityHashMap<>());
    private LongPredicate publicationCurrent;
    private final RgbSkyChannelEngine[] channels = new RgbSkyChannelEngine[3];
    public RgbSkyEngine(World world, WorldHeightContext height) {
        super(world, height);
        for (int c = 0; c < 3; c++) channels[c] = new RgbSkyChannelEngine(world, height, c, touched);
    }
    public void processColors(ChunkTasks task) {
        touched.clear();
        publicationCurrent = null;
        int x = CoordinateUtils.getChunkX(task.chunkCoordinate), z = CoordinateUtils.getChunkZ(task.chunkCoordinate);
        try {
            for (RgbSkyChannelEngine engine : channels) {
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
                if (task.queuedEdgeChecksSky != null) {
                    engine.checkChunkEdges(x, z, task.queuedEdgeChecksSky);
                    check(engine);
                }
            }
        } catch (Throwable error) { touched.clear(); throw error; }
    }
    public static final class QueueOverflow extends RuntimeException {}
    private static void check(PulsarEngine engine) {
        if (engine.wasQueueOverflowed()) throw new QueueOverflow();
    }
    public void relightColors(Map<Long, Chunk> targets, Map<Long, Chunk> available, LongPredicate current) {
        touched.clear();
        publicationCurrent = current;
        try {
            for (RgbSkyChannelEngine engine : channels) {
                engine.relightChunks(targets, available, current);
                check(engine);
            }
        } catch (Throwable error) { touched.clear(); throw error; }
    }
    public void publishColors() {
        try { publish(); } finally { touched.clear(); publicationCurrent = null; }
    }
    @Override
    protected void onNibbleVisible(int cacheIndex, com.sumirelabs.pulsar.light.SWMRNibbleArray nibble) {
        super.onNibbleVisible(cacheIndex, nibble);
        // Filters can leave every RGB component unchanged while gameplay skylight
        // changes in a neighboring chunk. That canonical plane must publish too.
        Chunk chunk = this.chunkCache[cacheIndex % 25];
        if (chunk != null) touched.add(chunk);
    }
    private void publish() {
        for (Chunk chunk : touched) synchronized (chunk) {
            if (publicationCurrent != null && !publicationCurrent.test(CoordinateUtils.getChunkKey(chunk.x, chunk.z))) continue;
            if (((ExtendedWorld) world).pulsar$getAnyChunkImmediately(chunk.x, chunk.z) != chunk) continue;
            RgbChunk rgb = (RgbChunk) chunk;
            RgbSnapshot previous = rgb.pulsar$getRgbSkySnapshot();
            RgbSnapshot next = rgb.pulsar$getRgbSkyStorage().skySnapshot(heightContext, previous == null ? 1 : previous.revision + 1,
                    ((com.sumirelabs.pulsar.light.PulsarChunk) chunk).pulsar$getSkyNibbles(), previous);
            if (previous == null || !previous.sameLightData(next)) {
                rgb.pulsar$setRgbSkySnapshot(next);
                RgbNetwork.changed(chunk);
            }
        }
    }
}
