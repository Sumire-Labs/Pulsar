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

/** One queue visit handles all three colors; canonical gameplay light uses its existing lane. */
abstract class PackedRgbChannelEngine extends PulsarEngine implements PackedRgbOwner {
    protected static final int RGB_DIRECTION_SHIFT = 40;
    protected static final int MIN_ABSORPTION = 0x010101;
    protected final PackedRgbCache rgb;
    private final BlockPos.MutableBlockPos samplePos = new BlockPos.MutableBlockPos();
    PackedRgbChannelEngine(boolean sky, World world, WorldHeightContext height, Set<Chunk> touched) {
        super(sky, world, height); rgb = new PackedRgbCache(this, sky, touched);
    }
    public void finishRgbTask() { rgb.finishTask(); }
    @Override protected void setNibblesForChunkInCache(int cx, int cz, SWMRNibbleArray[] red) { super.setNibblesForChunkInCache(cx, cz, red); rgb.bind(cx, cz, red); }
    @Override protected void updateVisible() { rgb.beforeVisible(); super.updateVisible(); }
    @Override protected void onNibbleVisible(int idx, SWMRNibbleArray nibble) { rgb.visible(idx); }
    @Override protected void onDestroyCaches() { rgb.destroy(); }
    @Override protected boolean[] getEmptinessMap(Chunk chunk) { return rgb.storage(chunk).empty[0]; }
    @Override protected void setEmptinessMap(Chunk chunk, boolean[] empty) { rgb.geometry(chunk, empty); }
    @Override protected SWMRNibbleArray[] getNibblesOnChunk(Chunk chunk) { return rgb.storage(chunk).channels[0]; }
    @Override protected void setNibbles(Chunk chunk, SWMRNibbleArray[] red) { rgb.save(chunk, red); }
    @Override protected boolean canUseChunk(Chunk chunk) { return ((PulsarChunk)chunk).pulsar$isLightUsable(); }
    @Override protected void initNibble(int x, int y, int z, boolean extrude, boolean initRemoved) { rgb.initBlock(x, y, z, initRemoved); }
    @Override protected void setNibbleNull(int x, int y, int z) { rgb.hide(x, y, z); }
    @Override protected int getDirectionShift() { return RGB_DIRECTION_SHIFT; }
    @Override protected long encodeQueueLevel(int level) { return PackedRgbLight.encodeQueueLevel(level); }
    @Override protected boolean isBelowPropagationThreshold(int level) { return PackedRgbLight.maxComponent(level) <= 1; }
    @Override protected boolean isMaxLight(int level) { return level == PackedRgbLight.ALL_CHANNELS; }
    @Override protected boolean areBothEdgeSectionsFull(int a, int b) { return rgb.full(a) && rgb.full(b); }
    @Override protected boolean areBothEdgeSectionsZero(int a, int b) { return rgb.zero(a) && rgb.zero(b); }
    protected final int section(int x, int y, int z) { return (x >> 4) + 5 * (z >> 4) + 25 * (y >> 4) + chunkSectionIndexOffset; }
    protected static int cell(int x, int y, int z) { return x & 15 | (z & 15) << 4 | (y & 15) << 8; }
    @Override protected int getLightLevel(int idx, int local) { return rgb.read(idx, local); }
    @Override protected int getLightLevel(int x, int y, int z) { return rgb.read(section(x, y, z), cell(x, y, z)); }
    @Override protected void setLightLevel(int x, int y, int z, int light) { rgb.write(section(x, y, z), cell(x, y, z), light); }
    protected final IBlockState fluid(int x, int y, int z) {
        return FluidLightBridge.rgbState(fluidCapCache[(x >> 4) + 5 * (z >> 4) + chunkIndexOffset], x, y, z);
    }
    protected final int emission(IBlockState state, int x, int y, int z) {
        return skylightPropagator ? 0 : RgbLightAccess.packedEmission(world, state, fluid(x, y, z), x, y, z, samplePos);
    }
    protected final int absorption(int info, IBlockState state, int dir, int x, int y, int z) {
        return RgbLightAccess.packedAbsorption(world, state, fluid(x, y, z), x, y, z, LightInfo.absorption(info, state, dir));
    }
    protected static long sided(int info) { return (info & LightInfo.SIDED) != 0 ? FLAG_HAS_SIDED_TRANSPARENT_BLOCKS : 0; }
    protected final long entry(int x, int y, int z, int level, long directions, long flags) {
        return encodeCoords(x, z, y, coordinateOffset) | encodeQueueLevel(level) | directions << RGB_DIRECTION_SHIFT | flags;
    }
    protected final int sourceFaces(long value, int x, int y, int z) {
        if ((value & FLAG_HAS_SIDED_TRANSPARENT_BLOCKS) == 0) return 0;
        return LightInfo.faceBits(lightInfoAt(getBlockState(x, y, z), x, y, z));
    }
    protected void beforePropagation(int x, int y, int z) {}
    @Override protected void performLightIncrease() {
        int read = 0, length = increaseQueueInitialLength;
        long[] queue = increaseQueue;
        while (read < length) {
            long value = queue[read++];
            int x = ((int)value & 63) - encodeOffsetX, z = ((int)(value >> 6) & 63) - encodeOffsetZ;
            int y = ((int)(value >> 12) & COORD_Y_MASK) - encodeOffsetY;
            beforePropagation(x, y, z);
            int level = PackedRgbLight.decodeQueueLevel(value), live = getLightLevel(x, y, z);
            if ((value & FLAG_WRITE_LEVEL) != 0) {
                level = PackedRgbLight.packedMax(level, live); setLightLevel(x, y, z, level);
            } else {
                // A change to one color must not discard valid queued work for the other two.
                level &= ~PackedRgbLight.channelPresenceMask(live ^ level);
                if (level == 0) continue;
            }
            int blocked = sourceFaces(value, x, y, z);
            AxisDirection[] directions = OLD_CHECK_DIRECTIONS[(int)(value >>> RGB_DIRECTION_SHIFT) & 63];
            for (AxisDirection direction : directions) {
                if ((blocked & 1 << direction.ordinal()) != 0) continue;
                int nx = x + direction.x, ny = y + direction.y, nz = z + direction.z, idx = section(nx, ny, nz);
                if (idx < 0 || idx >= nibbleCache.length || nibbleCache[idx] == null) continue;
                int local = cell(nx, ny, nz), current = getLightLevel(idx, local);
                int possible = PackedRgbLight.packedSubRGB(level, MIN_ABSORPTION);
                if (!PackedRgbLight.anyComponentGreater(possible, current)) continue;
                IBlockState state = getBlockStateFast(idx, nx & 15, ny & 15, nz & 15);
                boolean air = isPlainAir(state, idx);
                int info = air ? plainAirInfo() : lightInfoAt(state, nx, ny, nz);
                int candidate = air ? possible : PackedRgbLight.packedSubRGB(level, absorption(info, state, direction.oppositeOrdinal, nx, ny, nz));
                if (!PackedRgbLight.anyComponentGreater(candidate, current)) continue;
                int next = PackedRgbLight.packedMax(current, candidate);
                rgb.writeChanged(idx, local, next);
                if (PackedRgbLight.maxComponent(candidate) > 1) {
                    if (length >= queue.length) {
                        if (queue.length >= MAX_QUEUE_SIZE) { queueOverflowed = true; continue; }
                        queue = resizeIncreaseQueue();
                    }
                    queue[length++] = entry(nx, ny, nz, next, direction.everythingButTheOppositeDirection, sided(info));
                }
            }
        }
        lastBfsIncreaseTotal += read; increaseQueueInitialLength = 0;
    }
    @Override protected void performLightDecrease() {
        int read = 0, length = decreaseQueueInitialLength;
        long[] queue = decreaseQueue;
        while (read < length) {
            long value = queue[read++];
            int x = ((int)value & 63) - encodeOffsetX, z = ((int)(value >> 6) & 63) - encodeOffsetZ;
            int y = ((int)(value >> 12) & COORD_Y_MASK) - encodeOffsetY;
            beforePropagation(x, y, z);
            int level = PackedRgbLight.decodeQueueLevel(value), blocked = sourceFaces(value, x, y, z);
            AxisDirection[] directions = OLD_CHECK_DIRECTIONS[(int)(value >>> RGB_DIRECTION_SHIFT) & 63];
            for (AxisDirection direction : directions) {
                if ((blocked & 1 << direction.ordinal()) != 0) continue;
                int nx = x + direction.x, ny = y + direction.y, nz = z + direction.z, idx = section(nx, ny, nz);
                if (idx < 0 || idx >= nibbleCache.length || nibbleCache[idx] == null) continue;
                int local = cell(nx, ny, nz), current = getLightLevel(idx, local);
                if (current == 0) continue;
                IBlockState state = getBlockStateFast(idx, nx & 15, ny & 15, nz & 15);
                boolean air = isPlainAir(state, idx);
                int info = air ? plainAirInfo() : lightInfoAt(state, nx, ny, nz);
                int target = PackedRgbLight.packedSubRGB(level, air ? MIN_ABSORPTION : absorption(info, state, direction.oppositeOrdinal, nx, ny, nz));
                int keptMask = PackedRgbLight.maskWhereGreater(current, target);
                int kept = current & keptMask;
                if (kept != current) rgb.writeChanged(idx, local, kept);
                int source = air || keptMask == PackedRgbLight.ALL_CHANNELS ? 0 : emission(state, nx, ny, nz);
                int seed = PackedRgbLight.packedMax(kept, source);
                if (seed != 0) appendToIncreaseQueue(entry(nx, ny, nz, seed, ALL_DIRECTIONS_BITSET,
                        sided(info) | (seed == kept ? FLAG_RECHECK_LEVEL : FLAG_WRITE_LEVEL)));
                int decrease = target & ~keptMask & PackedRgbLight.channelPresenceMask(current);
                if (decrease != 0) {
                    if (length >= queue.length) {
                        if (queue.length >= MAX_QUEUE_SIZE) { queueOverflowed = true; continue; }
                        queue = resizeDecreaseQueue();
                    }
                    queue[length++] = entry(nx, ny, nz, decrease, direction.everythingButTheOppositeDirection, sided(info));
                }
            }
        }
        lastBfsDecreaseTotal += read; decreaseQueueInitialLength = 0; performLightIncrease();
    }
}
