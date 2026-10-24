/* SPDX-License-Identifier: LGPL-3.0-only
 * SuperNova-style packed propagation with Pulsar's tested 1.12.2 sky-source rules.
 */
package com.sumirelabs.pulsar.light.engine;

import com.sumirelabs.pulsar.rgb.PackedRgbLight;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import java.util.Set;

final class RgbPackedSkyChannelEngine extends PackedRgbChannelEngine {
    private final RgbPackedSkyColumnProcessor columns;
    RgbPackedSkyChannelEngine(World world, WorldHeightContext height, Set<Chunk> touched) {
        super(true, world, height, touched); columns = new RgbPackedSkyColumnProcessor(this);
    }
    @Override protected void initNibble(int cx, int cy, int cz, boolean extrude, boolean initRemoved) { columns.init(cx, cy, cz, extrude, initRemoved); }
    @Override protected void setNibbleNull(int cx, int cy, int cz) { columns.setNull(cx, cy, cz); }
    @Override protected void onDestroyCaches() { super.onDestroyCaches(); columns.clear(); }
    @Override protected void prepareBatchedEdgeChecks(int cx, int cz) { columns.prepareEdges(cx, cz); }
    @Override protected void beforePropagation(int x, int y, int z) { columns.checkNull(x >> 4, y >> 4, z >> 4, true); }
    @Override protected void processBlockPositionChanges(Chunk chunk, int cx, int cz, IntSet positions) { columns.changes(cx, cz, positions); }
    @Override protected void propagateBlockChanges(Chunk chunk, int x, int y, int z) {
        var positions = new it.unimi.dsi.fastutil.ints.IntOpenHashSet(); positions.add(y << 8 | (z & 15) << 4 | x & 15);
        columns.changes(chunk.x, chunk.z, positions);
    }
    @Override protected void lightChunk(Chunk chunk, boolean edges) { columns.light(chunk, edges); }
    void checkPackedEdges(Chunk chunk, int from, int to) { super.checkChunkEdges(chunk, from, to); }
    @Override protected void checkBlock(int x, int y, int z) {
        int current = getLightLevel(x, y, z);
        int sources = current & RgbPackedSkyColumnProcessor.fullMask(current);
        setLightLevel(x, y, z, sources);
        if (sources != 0) appendToIncreaseQueue(entry(x, y, z, sources, ALL_DIRECTIONS_BITSET, FLAG_HAS_SIDED_TRANSPARENT_BLOCKS));
        // Equal/full source values do not prove the set of open faces is unchanged.
        appendToDecreaseQueue(entry(x, y, z, current, ALL_DIRECTIONS_BITSET, 0));
    }
    @Override protected int calculateLightValue(int x, int y, int z, int expect) {
        int level = expect & RgbPackedSkyColumnProcessor.fullMask(expect);
        if (level == PackedRgbLight.ALL_CHANNELS) return level;
        var state = getBlockState(x, y, z); int info = lightInfoAt(state, x, y, z);
        for (var direction : AXIS_DIRECTIONS) {
            int nx = x + direction.x, ny = y + direction.y, nz = z + direction.z, idx = section(nx, ny, nz);
            if (idx < 0 || idx >= nibbleCache.length) continue;
            level = PackedRgbLight.packedMax(level, PackedRgbLight.packedSubRGB(getLightLevel(nx, ny, nz),
                    absorption(info, state, direction.ordinal(), x, y, z)));
        }
        return level;
    }
}
