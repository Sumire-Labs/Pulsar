/* SPDX-License-Identifier: LGPL-3.0-only
 * Pulsar's 1.12.2 sky-column rules, generalized to one packed RGB traversal.
 * Shared-queue design follows GTNewHorizons/SuperNova. Each component retains
 * Vanilla's full-strength direct-sky rule; weaker components use the shared BFS.
 */
package com.sumirelabs.pulsar.light.engine;

import com.sumirelabs.pulsar.light.SWMRNibbleArray;
import com.sumirelabs.pulsar.rgb.PackedRgbLight;
import com.sumirelabs.pulsar.rgb.RgbLightAccess;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.world.chunk.Chunk;
import java.util.Arrays;

final class RgbPackedSkyColumnProcessor {
    private final RgbPackedSkyChannelEngine engine;
    private final boolean[] checked;
    private final SWMRNibbleArray[][] removed;
    private final int[] columnY = new int[256], breakY = new int[3];
    RgbPackedSkyColumnProcessor(RgbPackedSkyChannelEngine engine) {
        this.engine = engine;
        checked = new boolean[engine.heightContext.getTotalLightSections()];
        removed = new SWMRNibbleArray[3][engine.nibbleCache.length];
    }
    private int index(int cx, int cy, int cz) { return cx + 5 * cz + 25 * cy + engine.chunkSectionIndexOffset; }
    static int fullMask(int value) {
        return ~PackedRgbLight.channelPresenceMask(value ^ PackedRgbLight.ALL_CHANNELS) & PackedRgbLight.ALL_CHANNELS;
    }
    void clear() { for (var plane : removed) Arrays.fill(plane, null); }
    void resetChecks() { Arrays.fill(checked, false); }
    void init(int cx, int cy, int cz, boolean extrude, boolean initRemoved) {
        if (cy < engine.minLightSection || cy > engine.maxLightSection || engine.getChunkInCache(cx, cz) == null) return;
        int idx = index(cx, cy, cz);
        if (engine.rgb.plane(0, idx) == null && !initRemoved) return;
        boolean needs = false;
        for (int c = 0; c < 3; c++) {
            var nib = engine.rgb.plane(c, idx); needs |= nib == null || nib.isNullNibbleUpdating();
        }
        if (!needs) return;
        engine.rgb.discardPacked(idx);
        int highest = engine.minSection - 1;
        boolean[] empty = engine.getEmptinessMap(cx, cz);
        for (int sy = engine.maxSection; sy >= engine.minSection; sy--) {
            if (empty != null ? !empty[sy - engine.minSection]
                    : engine.getChunkSection(cx, sy, cz) != null && !engine.getChunkSection(cx, sy, cz).isEmpty()) {
                highest = sy; break;
            }
        }
        for (int c = 0; c < 3; c++) {
            SWMRNibbleArray nib = engine.rgb.plane(c, idx);
            if (nib == null) {
                nib = removed[c][idx]; removed[c][idx] = null;
                if (nib == null) nib = new SWMRNibbleArray(null, true);
                engine.rgb.replacePlane(c, idx, nib);
            }
            if (!nib.isNullNibbleUpdating()) continue;
            if (cy > highest) { nib.setNonNull(); nib.setFull(); }
            else if (cy < engine.minSection) { nib.setNonNull(); nib.setZero(); }
            else if (extrude) {
                for (int above = cy + 1; above <= engine.maxLightSection; above++) {
                    SWMRNibbleArray source = engine.rgb.plane(c, index(cx, above, cz));
                    if (source != null && !source.isNullNibbleUpdating()) { nib.setNonNull(); nib.extrudeLower(source); break; }
                }
            } else nib.setNonNull();
        }
    }
    void setNull(int cx, int cy, int cz) {
        int idx = index(cx, cy, cz); engine.rgb.discardPacked(idx);
        for (int c = 0; c < 3; c++) if (engine.rgb.plane(c, idx) != null) engine.rgb.plane(c, idx).setNull();
    }
    void rewrite() {
        for (int idx = 0; idx < engine.nibbleCache.length; idx++) {
            if (engine.nibbleCache[idx] == null || engine.rgb.present(idx)) continue;
            engine.rgb.discardPacked(idx);
            boolean dirty = false;
            for (int c = 0; c < 3; c++) { removed[c][idx] = engine.rgb.plane(c, idx); dirty |= removed[c][idx] != null && removed[c][idx].isDirty(); }
            if (dirty) engine.publishCacheNibble(idx, engine.nibbleCache[idx], false);
            engine.rgb.removeFromCache(idx);
        }
    }
    boolean checkNull(int cx, int cy, int cz, boolean extrudeCenter) {
        if (cy < engine.minLightSection || cy > engine.maxLightSection || checked[cy - engine.minLightSection]) return false;
        checked[cy - engine.minLightSection] = true;
        boolean found = false;
        for (int dz = -1; dz <= 1 && !found; dz++) for (int dx = -1; dx <= 1; dx++)
            if (engine.rgb.present(index(cx + dx, cy, cz + dz))) { found = true; break; }
        if (found) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++)
            init(cx + dx, cy, cz + dz, (dx | dz) != 0 || extrudeCenter, true);
        return found;
    }
    void prepareEdges(int cx, int cz) {
        resetChecks(); rewrite();
        for (int sy = engine.maxLightSection; sy >= engine.minLightSection; sy--) checkNull(cx, sy, cz, true);
    }
    private int extruded(int x, int y, int z) {
        int result = 0;
        for (int c = 0; c < 3; c++) {
            int value = 15;
            for (int sy = y >> 4; sy <= engine.maxLightSection; sy++) {
                if (sy < engine.minLightSection) continue;
                var nibble = engine.rgb.plane(c, index(x >> 4, sy, z >> 4));
                if (nibble == null || nibble.isNullNibbleUpdating()) continue;
                int yy = sy == y >> 4 ? y & 15 : 0;
                value = PackedRgbLight.toCompact(engine.getLightLevel(index(x >> 4, sy, z >> 4), (x & 15) | (z & 15) << 4 | yy << 8))
                        >>> ((2 - c) * 4) & 15;
                break;
            }
            result |= value << ((2 - c) * 8);
        }
        return result;
    }
    private void direct(int x, int startY, int z, boolean extrude, boolean delayed) {
        Arrays.fill(breakY, startY);
        int active = fullMask(extruded(x, startY + 1, z));
        if (active == 0) return;
        checkNull(x >> 4, startY >> 4, z >> 4, extrude);
        int aboveInfo = engine.lightInfoAt(engine.getBlockState(x, startY + 1, z), x, startY + 1, z);
        long directions = PulsarEngine.AxisDirection.POSITIVE_Y.everythingButThisDirection;
        int y = startY;
        for (; y >= engine.minLightSection << 4; y--) {
            if ((y & 15) == 15) checkNull(x >> 4, y >> 4, z >> 4, extrude);
            var state = engine.getBlockState(x, y, z); int info = engine.lightInfoAt(state, x, y, z);
            int stopped;
            if (!LightAttenuation.canExtrudeDirectSky(15, LightInfo.opacity(aboveInfo), LightInfo.opacity(info))) stopped = active;
            else stopped = active & PackedRgbLight.channelPresenceMask(RgbLightAccess.packedAbsorption(
                    engine.world, state, engine.fluid(x, y, z), x, y, z, 0));
            for (int c = 0; c < 3; c++) if ((stopped & 15 << ((2 - c) * 8)) != 0) breakY[c] = y;
            active &= ~stopped;
            if (active == 0) break;
            boolean appended = engine.appendToIncreaseQueue(engine.entry(x, y, z, active, directions, 0));
            if (engine.getNibbleFromCache(x >> 4, y >> 4, z >> 4) == null) {
                if (appended) --engine.increaseQueueInitialLength;
                y &= ~15; aboveInfo = LightInfo.of(net.minecraft.init.Blocks.AIR.getDefaultState());
            } else {
                if (!delayed) engine.setLightLevel(x, y, z, PackedRgbLight.packedMax(engine.getLightLevel(x, y, z), active));
                aboveInfo = info;
            }
        }
        for (int c = 0; c < 3; c++) if ((active & 15 << ((2 - c) * 8)) != 0) breakY[c] = y;
    }
    private void decreaseColumn(int x, int z) {
        // Components can stop at different filters. Read geometry/levels once for all three.
        int top = Math.max(breakY[0], Math.max(breakY[1], breakY[2]));
        int stopped = 0;
        for (int y = top; y >= engine.minLightSection << 4; y--) {
            int eligible = 0;
            for (int c = 0; c < 3; c++) if (y <= breakY[c] && (stopped & 1 << c) == 0) eligible |= 15 << ((2 - c) * 8);
            if (eligible == 0) continue;
            checkNull(x >> 4, y >> 4, z >> 4, true);
            int idx = index(x >> 4, y >> 4, z >> 4);
            if (engine.nibbleCache[idx] == null) { y &= ~15; continue; }
            int mask = fullMask(engine.getLightLevel(x, y, z)) & eligible;
            for (int c = 0; c < 3; c++) {
                int bits = 15 << ((2 - c) * 8);
                if ((eligible & bits) != 0 && (mask & bits) == 0) stopped |= 1 << c;
            }
            if (mask != 0) engine.appendToDecreaseQueue(engine.entry(x, y, z, mask,
                    PulsarEngine.AxisDirection.POSITIVE_Y.everythingButThisDirection, 0));
            if (stopped == 7) break;
        }
    }
    private void applyDelayed() {
        for (int i = 0; i < engine.increaseQueueInitialLength; i++) {
            long entry = engine.increaseQueue[i]; int x = ((int)entry & 63) - engine.encodeOffsetX;
            int z = ((int)(entry >> 6) & 63) - engine.encodeOffsetZ, y = ((int)(entry >> 12) & 0xFFFF) - engine.encodeOffsetY;
            engine.setLightLevel(x, y, z, PackedRgbLight.packedMax(engine.getLightLevel(x, y, z), PackedRgbLight.decodeQueueLevel(entry)));
        }
        for (int i = 0; i < engine.decreaseQueueInitialLength; i++) {
            long entry = engine.decreaseQueue[i]; int x = ((int)entry & 63) - engine.encodeOffsetX;
            int z = ((int)(entry >> 6) & 63) - engine.encodeOffsetZ, y = ((int)(entry >> 12) & 0xFFFF) - engine.encodeOffsetY;
            int mask = PackedRgbLight.channelPresenceMask(PackedRgbLight.decodeQueueLevel(entry));
            engine.setLightLevel(x, y, z, engine.getLightLevel(x, y, z) & ~mask);
        }
    }
    void changes(int cx, int cz, IntSet positions) {
        resetChecks(); rewrite(); Arrays.fill(columnY, Integer.MIN_VALUE);
        var it = positions.iterator();
        while (it.hasNext()) { int p = it.nextInt(), y = p >> 8; if (engine.heightContext.containsBlockY(y)) columnY[p & 255] = Math.max(columnY[p & 255], y); }
        for (int col = 0; col < 256; col++) if (columnY[col] != Integer.MIN_VALUE) {
            int x = cx << 4 | col & 15, z = cz << 4 | col >> 4;
            direct(x, columnY[col], z, true, true); decreaseColumn(x, z);
        }
        applyDelayed(); it = positions.iterator();
        while (it.hasNext()) { int p = it.nextInt(), y = p >> 8; if (engine.heightContext.containsBlockY(y)) {
            engine.lastPositionsProcessed++; engine.checkBlock(cx << 4 | p & 15, y, cz << 4 | p >> 4 & 15);
        }}
        engine.performLightDecrease();
    }
    void light(Chunk chunk, boolean edges) {
        resetChecks(); rewrite();
        int highest = engine.maxSection; var sections = chunk.getBlockStorageArray();
        for (; highest >= engine.minSection; highest--) {
            int slot = engine.heightContext.getStorageIndex(highest);
            if (slot >= 0 && slot < sections.length && sections[slot] != null && !sections[slot].isEmpty()) break;
            checkNull(chunk.x, highest, chunk.z, false);
            for (var dir : PulsarEngine.ONLY_HORIZONTAL_DIRECTIONS) {
                if (engine.getNibbleFromCache(chunk.x + dir.x, highest, chunk.z + dir.z) == null) continue;
                int sx = chunk.x << 4 | (dir.x > 0 ? 15 : 0), sz = chunk.z << 4 | (dir.z > 0 ? 15 : 0);
                for (int y = highest << 4; y <= (highest << 4 | 15); y++) for (int i = 0; i < 16; i++)
                    engine.appendToIncreaseQueue(engine.entry(sx + (dir.x == 0 ? i : 0), y,
                            sz + (dir.z == 0 ? i : 0), PackedRgbLight.ALL_CHANNELS, 1L << dir.ordinal(), 0));
            }
        }
        if (highest >= engine.minSection)
            for (int z = chunk.z << 4; z < (chunk.z + 1) << 4; z++) for (int x = chunk.x << 4; x < (chunk.x + 1) << 4; x++)
                direct(x, (highest << 4 | 15) + 1, z, false, false);
        if (edges) engine.performLightIncrease();
        for (int y = highest; y >= engine.minLightSection; y--) checkNull(chunk.x, y, chunk.z, false);
        if (edges) engine.checkPackedEdges(chunk, engine.minLightSection, highest);
        else { engine.propagateNeighbourLevels(chunk, engine.minLightSection, highest); engine.performLightIncrease(); }
    }
}
