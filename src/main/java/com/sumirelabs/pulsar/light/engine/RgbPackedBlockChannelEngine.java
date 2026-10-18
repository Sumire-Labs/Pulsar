/* SPDX-License-Identifier: LGPL-3.0-only
 * Packed RGB propagation adapted from GTNewHorizons/SuperNova at
 * 22d8559c3064d77d115e58523c58cde824f4e214, with Pulsar's 1.12.2 state,
 * fluid/context snapshots and independent scalar gameplay lighting.
 */
package com.sumirelabs.pulsar.light.engine;

import com.sumirelabs.pulsar.compat.FluidLightBridge;
import com.sumirelabs.pulsar.light.PulsarChunk;
import com.sumirelabs.pulsar.light.SWMRNibbleArray;
import com.sumirelabs.pulsar.rgb.*;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import java.util.Set;

/** Packed block-light source scanning and change seeding. */
final class RgbPackedBlockChannelEngine extends PackedRgbChannelEngine {
    RgbPackedBlockChannelEngine(World world, WorldHeightContext height, Set<Chunk> touched) {
        super(false, world, height, touched);
    }
    @Override protected void checkBlock(int x, int y, int z) {
        int previous = getLightLevel(x, y, z);
        IBlockState state = getBlockState(x, y, z);
        int info = lightInfoAt(state, x, y, z), source = emission(state, x, y, z);
        setLightLevel(x, y, z, source);
        if (source != 0) appendToIncreaseQueue(entry(x, y, z, source, ALL_DIRECTIONS_BITSET, sided(info) | FLAG_WRITE_LEVEL));
        // A zero old value is still useful: the decrease phase re-seeds adjacent sources
        // after an opaque block becomes transparent. Never mask this with the new faces.
        appendToDecreaseQueue(entry(x, y, z, previous, ALL_DIRECTIONS_BITSET, 0));
    }
    @Override protected int calculateLightValue(int x, int y, int z, int expect) {
        IBlockState state = getBlockState(x, y, z); int info = lightInfoAt(state, x, y, z);
        int level = emission(state, x, y, z);
        for (AxisDirection direction : AXIS_DIRECTIONS) {
            int nx = x + direction.x, ny = y + direction.y, nz = z + direction.z;
            int candidate = PackedRgbLight.packedSubRGB(getLightLevel(nx, ny, nz), absorption(info, state, direction.ordinal(), x, y, z));
            level = PackedRgbLight.packedMax(level, candidate);
        }
        return level;
    }
    @Override protected void propagateBlockChanges(Chunk chunk, int x, int y, int z) { checkBlock(x, y, z); performLightDecrease(); }
    @Override protected void processBlockPositionChanges(Chunk chunk, int cx, int cz, IntSet positions) {
        IntIterator it = positions.iterator();
        while (it.hasNext()) {
            int packed = it.nextInt(), y = packed >> 8;
            if (!heightContext.containsBlockY(y)) continue;
            ++lastPositionsProcessed; checkBlock((cx << 4) | packed & 15, y, (cz << 4) | packed >> 4 & 15);
        }
        performLightDecrease();
    }
    @Override protected void lightChunk(Chunk chunk, boolean needsEdgeChecks) {
        var sections = chunk.getBlockStorageArray();
        Object capability = fluidCapCache[chunk.x + 5 * chunk.z + chunkIndexOffset];
        for (int sy = minSection; sy <= maxSection; sy++) {
            int index = heightContext.getStorageIndex(sy);
            var section = index < 0 || index >= sections.length ? null : sections[index];
            boolean fluids = FluidLightBridge.mayHaveFluidStates(capability, sy, sy);
            boolean absolute = section != null && (!(section.getData() instanceof RgbFilterPalette palette) || palette.pulsar$mayEmitAbsoluteRgb());
            if (!fluids && !absolute && (section == null || section.isEmpty()
                    || section.getData() instanceof com.sumirelabs.pulsar.light.ContextualLightPalette palette
                    && (palette.pulsar$lightPaletteFlags() & com.sumirelabs.pulsar.light.ContextualLightPalette.MAY_EMIT) == 0)) continue;
            int idx = chunk.x + 5 * chunk.z + 25 * sy + chunkSectionIndexOffset;
            for (int local = 0; local < 4096; local++) {
                int x = chunk.x * 16 + (local & 15), y = sy * 16 + (local >> 8), z = chunk.z * 16 + (local >> 4 & 15);
                IBlockState state = getBlockStateFast(idx, x & 15, y & 15, z & 15);
                int source = emission(state, x, y, z), current = getLightLevel(idx, local);
                if (!PackedRgbLight.anyComponentGreater(source, current)) continue;
                int merged = PackedRgbLight.packedMax(current, source);
                setLightLevel(x, y, z, merged);
                appendToIncreaseQueue(entry(x, y, z, merged, ALL_DIRECTIONS_BITSET, sided(lightInfoAt(state, x, y, z))));
            }
        }
        if (needsEdgeChecks) { performLightIncrease(); checkChunkEdges(chunk, minLightSection, maxLightSection); }
        else { propagateNeighbourLevels(chunk, minLightSection, maxLightSection); performLightIncrease(); }
    }
}
