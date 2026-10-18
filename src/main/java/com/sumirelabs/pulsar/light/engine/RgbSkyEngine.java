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

/** Packed RGB sky lane with a filter-free white shortcut and selectable gameplay light.
 * All components execute under one scheduler reservation; snapshots publish only after all finish.
 */
public final class RgbSkyEngine extends ScalarSkyEngine {
    private final Set<Chunk> touched = Collections.newSetFromMap(new IdentityHashMap<>());
    private LongPredicate publicationCurrent;
    private int colorPositions, colorIncrease, colorDecrease;
    public int colorPositions(){return colorPositions;}
    public int colorIncrease(){return colorIncrease;}
    public int colorDecrease(){return colorDecrease;}
    private PulsarEngine[] channels;
    private boolean white;
    private int whiteX, whiteZ;
    private final RgbSkyWhiteRegion.Lookup filterLookup = this::hasFilters;
    public RgbSkyEngine(World world, WorldHeightContext height) {
        super(world, height);
    }
    private void ensureChannels() {
        if (channels == null) {
            if (Boolean.parseBoolean(System.getProperty("pulsar.rgb.packed", "true"))) channels = new PulsarEngine[]{new RgbPackedSkyChannelEngine(world, heightContext, touched)};
            else {
                channels = new PulsarEngine[3];
                for (int c = 0; c < 3; c++) channels[c] = new RgbSkyChannelEngine(world, heightContext, c, touched);
            }
        }
    }
    private boolean hasFilters(int x, int z) {
        Chunk chunk = ((ExtendedWorld)world).pulsar$getAnyChunkImmediately(x, z);
        if (chunk == null) return true;
        for (var section : chunk.getBlockStorageArray()) if (section != null) {
            Object data = section.getData();
            if (!(data instanceof RgbFilterPalette palette) || palette.pulsar$mayFilterSky()) return true;
        }
        return false;
    }
    public void processColors(ChunkTasks task) {
        touched.clear();
        colorPositions=colorIncrease=colorDecrease=0;
        publicationCurrent = null;
        int x = CoordinateUtils.getChunkX(task.chunkCoordinate), z = CoordinateUtils.getChunkZ(task.chunkCoordinate);
        if (task.initialLightChunk != null || task.loadInitChunk != null || task.changedSectionSet != null
                || (task.changedPositions != null && !task.changedPositions.isEmpty())) {
            Chunk changed = ((ExtendedWorld)world).pulsar$getAnyChunkImmediately(x, z);
            if (changed != null) for (var section : changed.getBlockStorageArray())
                if (section != null && section.getData() instanceof RgbFilterPalette palette)
                    palette.pulsar$invalidateSkyFilterCache();
        }
        white = RgbSkyWhiteRegion.canUseWhite(x, z, filterLookup);
        if (white) {
            whiteX = x; whiteZ = z;
            // Removing the last filter can change only RGB, leaving canonical
            // sky untouched. Explicitly reset every formerly colored neighbor.
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                Chunk chunk = ((ExtendedWorld)world).pulsar$getAnyChunkImmediately(x + dx, z + dz);
                if (chunk != null && (((RgbChunk)chunk).pulsar$getRgbSkyStorage() != null
                        || ((RgbChunk)chunk).pulsar$getRgbSkySnapshot() == null)) touched.add(chunk);
            }
            return;
        }
        ensureChannels();
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
                if (task.queuedEdgeChecksSky != null) {
                    engine.checkChunkEdges(x, z, task.queuedEdgeChecksSky);
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
        white = false;
        ensureChannels();
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
    public boolean requiresScalarLighting(){return !RgbSettings.projectsGameplayLight() || white;}
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
    @Override
    protected void setNibbles(Chunk chunk, com.sumirelabs.pulsar.light.SWMRNibbleArray[] nibbles) {
        super.setNibbles(chunk, nibbles);
        touched.add(chunk);
    }
    private void publish() {
        for (Chunk chunk : touched) synchronized (chunk) {
            if (white && !RgbSkyWhiteRegion.contains(whiteX, whiteZ, chunk.x, chunk.z)) continue;
            if (publicationCurrent != null && !publicationCurrent.test(CoordinateUtils.getChunkKey(chunk.x, chunk.z))) continue;
            if (((ExtendedWorld) world).pulsar$getAnyChunkImmediately(chunk.x, chunk.z) != chunk) continue;
            RgbChunk rgb = (RgbChunk) chunk;
            RgbSnapshot previous = rgb.pulsar$getRgbSkySnapshot();
            long revision = previous == null ? 1 : previous.revision + 1;
            var canonical = ((com.sumirelabs.pulsar.light.PulsarChunk) chunk).pulsar$getSkyNibbles();
            RgbSnapshot next;
            if (white) {
                next = RgbStorage.whiteSkySnapshot(heightContext.getMinLightSection(), revision, canonical, previous);
                rgb.pulsar$setRgbSkyStorage(null);
            } else {
                RgbStorage storage = rgb.pulsar$getRgbSkyStorage();
                if (storage == null) {
                    storage = RgbStorage.fromSky(heightContext, canonical,
                            ((com.sumirelabs.pulsar.light.PulsarChunk)chunk).pulsar$getSkyEmptinessMap());
                    rgb.pulsar$setRgbSkyStorage(storage);
                }
                next = RgbSettings.projectsGameplayLight() ? storage.maximumSnapshot(revision, true, previous)
                        : storage.skySnapshot(heightContext, revision, canonical, previous);
                if (RgbSettings.projectsGameplayLight()) RgbCanonicalLight.install(chunk, next, true, storage.empty[0], heightContext);
            }
            if (previous == null || !previous.sameLightData(next)) {
                rgb.pulsar$setRgbSkySnapshot(next);
                RgbNetwork.changed(chunk, previous == null || !previous.sameCanonical(next));
            }
        }
    }
}
