/* SPDX-License-Identifier: LGPL-3.0-only
 * Packed-cache design adapted from GTNewHorizons/SuperNova SupernovaRGBEngine,
 * commit 22d8559c3064d77d115e58523c58cde824f4e214. Pulsar retains independent
 * canonical light and generation-safe chunk publication.
 */
package com.sumirelabs.pulsar.light.engine;

import com.sumirelabs.pulsar.light.SWMRNibbleArray;
import com.sumirelabs.pulsar.rgb.*;
import com.sumirelabs.pulsar.util.CoordinateUtils;
import net.minecraft.world.chunk.Chunk;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Task-scoped packed RGB values with private extra planes for initial/range lighting. */
final class PackedRgbCache {
    private record Owned(Chunk chunk, RgbStorage storage) {}
    private final PulsarEngine engine;
    private final boolean sky;
    private final Set<Chunk> touched;
    private final Map<Long, Owned> building = new HashMap<>();
    private final SWMRNibbleArray[][] planes;
    private final int[][] packed;
    // Cells are 0..4095; 4096/-1 are the empty-range sentinels.
    private final short[] dirtyMin, dirtyMax;
    private final boolean[] boundChunks = new boolean[25];

    PackedRgbCache(PulsarEngine engine, boolean sky, Set<Chunk> touched) {
        this.engine = engine; this.sky = sky; this.touched = touched;
        int count = engine.nibbleCache.length;
        planes = new SWMRNibbleArray[3][count]; packed = new int[count][];
        dirtyMin = new short[count]; dirtyMax = new short[count];
        Arrays.fill(dirtyMin, (short)4096); Arrays.fill(dirtyMax, (short)-1);
    }
    RgbStorage storage(Chunk chunk) {
        Owned privateEntry = building.get(CoordinateUtils.getChunkKey(chunk.x, chunk.z));
        if (privateEntry != null && privateEntry.chunk == chunk) return privateEntry.storage;
        RgbChunk rgb = (RgbChunk) chunk;
        RgbStorage storage = sky ? rgb.pulsar$getRgbSkyStorage() : rgb.pulsar$getRgbStorage();
        if (storage == null) {
            storage = sky ? RgbStorage.fromSky(engine.heightContext,
                    ((com.sumirelabs.pulsar.light.PulsarChunk) chunk).pulsar$getSkyNibbles(),
                    ((com.sumirelabs.pulsar.light.PulsarChunk) chunk).pulsar$getSkyEmptinessMap())
                    : new RgbStorage(engine.minLightSection, engine.heightContext.getTotalLightSections());
            install(chunk, storage);
        }
        return storage;
    }
    private void install(Chunk chunk, RgbStorage storage) {
        if (sky) ((RgbChunk)chunk).pulsar$setRgbSkyStorage(storage);
        else ((RgbChunk)chunk).pulsar$setRgbStorage(storage);
    }
    void bind(int cx, int cz, SWMRNibbleArray[] red) {
        Chunk chunk = engine.getChunkInCache(cx, cz);
        if (chunk == null) return;
        boundChunks[cx + 5 * cz + engine.chunkIndexOffset] = true;
        RgbStorage storage = storage(chunk);
        if (storage.channels[0] != red) {
            storage = new RgbStorage(engine.minLightSection, engine.heightContext.getTotalLightSections());
            storage.channels[0] = red;
            building.put(CoordinateUtils.getChunkKey(cx, cz), new Owned(chunk, storage));
        }
        for (int cy = engine.minLightSection; cy <= engine.maxLightSection; cy++) {
            int idx = cx + 5 * cz + 25 * cy + engine.chunkSectionIndexOffset;
            for (int c = 0; c < 3; c++) planes[c][idx] = storage.channels[c] == null ? null : storage.channels[c][cy - engine.minLightSection];
        }
    }
    void save(Chunk chunk, SWMRNibbleArray[] red) {
        RgbStorage storage = storage(chunk); storage.channels[0] = red;
        install(chunk, storage); touched.add(chunk);
    }
    void geometry(Chunk chunk, boolean[] empty) {
        RgbStorage storage = storage(chunk);
        for (int c = 0; c < 3; c++) storage.empty[c] = empty;
    }
    SWMRNibbleArray plane(int channel, int idx) { return planes[channel][idx]; }
    void replacePlane(int channel, int idx, SWMRNibbleArray nibble) {
        discardPacked(idx);
        planes[channel][idx] = nibble;
        if (channel == 0) engine.nibbleCache[idx] = nibble;
        Chunk chunk = engine.chunkCache[idx % 25];
        if (chunk != null && boundChunks[idx % 25]) {
            int slot = idx / 25 - engine.chunkOffsetY - engine.minLightSection;
            RgbStorage storage = storage(chunk);
            if (slot >= 0 && slot < storage.channels[channel].length) storage.channels[channel][slot] = nibble;
        }
    }
    boolean present(int idx) {
        for (int c = 0; c < 3; c++) if (planes[c][idx] != null && !planes[c][idx].isNullNibbleUpdating()) return true;
        return false;
    }
    void removeFromCache(int idx) {
        discardPacked(idx);
        for (var plane : planes) plane[idx] = null;
        engine.nibbleCache[idx] = null;
    }
    void discardPacked(int idx) {
        unpack(idx);
        if (packed[idx] != null) {
            var pool = PulsarEngine.PACKED_ARRAY_POOL.get();
            if (pool.size() < 32) pool.addFirst(packed[idx]);
            packed[idx] = null;
        }
    }
    int read(int idx, int cell) {
        int[] values = packed[idx];
        if (values != null) return values[cell];
        boolean allZero = true, allFull = true;
        for (int c = 0; c < 3; c++) {
            var nibble = planes[c][idx];
            allZero &= nibble == null || nibble.isZeroUpdating();
            allFull &= nibble != null && nibble.isFullUpdating();
        }
        if (allZero) return 0;
        if (allFull) return PackedRgbLight.ALL_CHANNELS;
        return materialize(idx)[cell];
    }
    private int[] materialize(int idx) {
        int[] values = packed[idx];
        if (values != null) return values;
        values = PulsarEngine.acquirePackedArray();
        packPlanes(planes[0][idx], planes[1][idx], planes[2][idx], values);
        return packed[idx] = values;
    }
    /** Read-only views: full constants must not be made writable merely to pack them. */
    private static byte[] partialBytes(SWMRNibbleArray nibble) {
        return nibble == null || nibble.isZeroUpdating() || nibble.isFullUpdating()
                ? null : nibble.getUpdatingData();
    }
    static void packPlanes(SWMRNibbleArray r, SWMRNibbleArray g, SWMRNibbleArray b, int[] values) {
        byte[] rb = partialBytes(r), gb = partialBytes(g), bb = partialBytes(b);
        int rf = r != null && r.isFullUpdating() ? 255 : 0;
        int gf = g != null && g.isFullUpdating() ? 255 : 0;
        int bf = b != null && b.isFullUpdating() ? 255 : 0;
        for (int i = 0; i < 2048; i++) {
            int red = rb == null ? rf : rb[i] & 255;
            int green = gb == null ? gf : gb[i] & 255;
            int blue = bb == null ? bf : bb[i] & 255;
            values[i * 2] = (red & 15) << 16 | (green & 15) << 8 | blue & 15;
            values[i * 2 + 1] = (red & 240) << 12 | (green & 240) << 4 | blue >>> 4;
        }
    }
    void write(int idx, int cell, int rgb) {
        if (planes[0][idx] == null) return;
        if (read(idx, cell) == rgb) return;
        writeChanged(idx, cell, rgb);
    }
    /** Caller already resolved this section/cell and proved the value changes. */
    void writeChanged(int idx, int cell, int rgb) {
        materialize(idx)[cell] = rgb;
        dirtyMin[idx] = (short)Math.min(dirtyMin[idx], cell); dirtyMax[idx] = (short)Math.max(dirtyMax[idx], cell);
        engine.postLightUpdate(idx, cell & 15, cell >> 8, cell >> 4 & 15);
    }
    void initBlock(int cx, int cy, int cz, boolean initRemoved) {
        if (cy < engine.minLightSection || cy > engine.maxLightSection || engine.getChunkInCache(cx, cz) == null) return;
        int idx = cx + 5 * cz + 25 * cy + engine.chunkSectionIndexOffset;
        if (planes[0][idx] == null && !initRemoved) return;
        for (int c = 0; c < 3; c++) {
            SWMRNibbleArray nib = planes[c][idx];
            if (nib == null) {
                nib = planes[c][idx] = new SWMRNibbleArray();
                if (c == 0) engine.setNibbleInCache(cx, cy, cz, nib);
                else storage(engine.getChunkInCache(cx, cz)).channels[c][cy - engine.minLightSection] = nib;
            } else nib.setNonNull();
        }
    }
    void hide(int cx, int cy, int cz) {
        int idx = cx + 5 * cz + 25 * cy + engine.chunkSectionIndexOffset;
        unpack(idx);
        for (int c = 0; c < 3; c++) if (planes[c][idx] != null) planes[c][idx].setHidden();
    }
    private void unpack(int idx) {
        int min = dirtyMin[idx], max = dirtyMax[idx];
        if (max < min) return;
        int[] values = packed[idx];
        for (int c = 0; c < 3; c++) unpackPlane(planes[c][idx], values, min, max, (2 - c) * 8);
        dirtyMin[idx] = 4096; dirtyMax[idx] = -1;
    }
    static void unpackPlane(SWMRNibbleArray nibble, int[] values, int min, int max, int shift) {
        if (nibble == null) return;
        boolean full = nibble.isFullUpdating(), writable = false;
        byte[] bytes = partialBytes(nibble);
        for (int i = min >> 1, end = max >> 1; i <= end; i++) {
            int next = (values[i * 2] >>> shift & 15) | (values[i * 2 + 1] >>> shift & 15) << 4;
            int previous = bytes == null ? (full ? 255 : 0) : bytes[i] & 255;
            if (previous == next) continue;
            if (!writable) {
                // Detach from visible/shared storage before the first real change.
                nibble.markDirtyAll(); bytes = nibble.getUpdatingStorage(); writable = true;
            }
            bytes[i] = (byte)next;
        }
    }
    void beforeVisible() {
        for (int idx = 0; idx < packed.length; idx++) {
            unpack(idx);
            // A green/blue-only edit must publish even if red did not change.
            if (planes[1][idx] != null && planes[1][idx].isDirty()
                    || planes[2][idx] != null && planes[2][idx].isDirty()) engine.notifyUpdateCache[idx] = true;
        }
    }
    void visible(int idx) {
        if (!boundChunks[idx % 25]) return; // Two-radius topology scratch is never a chunk publication.
        for (int c = 1; c < 3; c++) if (planes[c][idx] != null) planes[c][idx].updateVisible();
        Chunk chunk = engine.chunkCache[idx % 25]; if (chunk != null) touched.add(chunk);
    }
    boolean full(int idx) {
        if (packed[idx] != null) {
            for (int value : packed[idx]) if (value != PackedRgbLight.ALL_CHANNELS) return false;
            return true;
        }
        for (int c = 0; c < 3; c++) if (planes[c][idx] == null || !planes[c][idx].isFullUpdating()) return false;
        return true;
    }
    boolean zero(int idx) {
        if (packed[idx] != null) { for (int value : packed[idx]) if (value != 0) return false; return true; }
        for (int c = 0; c < 3; c++) if (planes[c][idx] != null && !planes[c][idx].isZeroUpdating()) return false;
        return true;
    }
    void destroy() {
        for (int idx = 0; idx < packed.length; idx++) {
            // Range construction does not publish each halo; retain its updating data privately.
            unpack(idx);
            if (packed[idx] != null) {
                var pool = PulsarEngine.PACKED_ARRAY_POOL.get();
                if (pool.size() < 32) pool.addFirst(packed[idx]);
                packed[idx] = null;
            }
        }
        for (var plane : planes) Arrays.fill(plane, null);
        Arrays.fill(boundChunks, false);
        Arrays.fill(engine.notifyUpdateCache, false);
    }
    void finishTask() { building.clear(); }
}
