package com.sumirelabs.pulsar.rgb;

import com.sumirelabs.pulsar.api.*;
import com.sumirelabs.pulsar.compat.FluidLightBridge;
import com.sumirelabs.pulsar.light.ContextualLightSnapshot;
import com.sumirelabs.pulsar.light.WorldLightManager;
import com.sumirelabs.pulsar.util.CoordinateUtils;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Sparse world-thread samples for position-dependent RGB APIs. No worker invokes a mod callback. */
public final class RgbContextualLightManager {
    private static final class Entry {
        final Chunk chunk;
        final ContextualLightSnapshot<IBlockState> snapshot = new ContextualLightSnapshot<>();
        final IntOpenHashSet dynamic = new IntOpenHashSet(); // owner thread only
        Entry(Chunk chunk) { this.chunk = chunk; }
    }
    private final World world;
    private final WorldHeightContext height;
    private final Thread owner = Thread.currentThread();
    private final ConcurrentHashMap<Long, Entry> chunks = new ConcurrentHashMap<>();
    private final Set<Entry> pending = ConcurrentHashMap.newKeySet();
    private final Set<Entry> tracked = new HashSet<>();

    public RgbContextualLightManager(World world, WorldHeightContext height) {
        this.world = world; this.height = height;
    }
    private void requireOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("RGB callbacks require the world thread");
    }
    private static int key(int x, int y, int z) { return y << 8 | (z & 15) << 4 | x & 15; }
    private Entry entry(int x, int z) { return chunks.get(CoordinateUtils.getChunkKey(x >> 4, z >> 4)); }
    private static boolean dynamic(IBlockState state) { return (RgbStateProperties.of(state) & RgbStateProperties.DYNAMIC) != 0; }
    public static int properties(World world, IBlockState state, int x, int y, int z) {
        int fallback = RgbStateProperties.of(state);
        if ((fallback & RgbStateProperties.DYNAMIC) == 0) return fallback;
        var manager = ((com.sumirelabs.pulsar.world.PulsarWorld) world).pulsar$getLightManager();
        var samples = manager == null ? null : manager.rgbContextualLight();
        return samples == null ? fallback : samples.read(state, x, y, z, fallback);
    }

    public int read(IBlockState state, int x, int y, int z, int fallback) {
        Entry entry = entry(x, z);
        if (entry == null || !height.containsBlockY(y)) return fallback;
        int value = entry.snapshot.read(key(x, y, z), state, fallback);
        if (entry.snapshot.hasPending()) pending.add(entry);
        return value;
    }
    public void request(int x, int y, int z) {
        requestCell(x, y, z);
        requestCell(x - 1, y, z); requestCell(x + 1, y, z);
        requestCell(x, y - 1, z); requestCell(x, y + 1, z);
        requestCell(x, y, z - 1); requestCell(x, y, z + 1);
    }
    private void requestCell(int x, int y, int z) {
        Entry entry = entry(x, z);
        if (entry == null || !height.containsBlockY(y)) return;
        int key = key(x, y, z);
        if (Thread.currentThread() == owner && !FluidLightBridge.LOADED
                && !entry.snapshot.contains(key) && !dynamic(entry.chunk.getBlockState(x, y, z))) return;
        entry.snapshot.request(key); pending.add(entry);
    }
    public boolean hasPending(int cx, int cz) {
        Entry entry = chunks.get(CoordinateUtils.getChunkKey(cx, cz));
        return entry != null && entry.snapshot.hasPending();
    }
    public void unload(int cx, int cz) {
        requireOwner();
        Entry old = chunks.remove(CoordinateUtils.getChunkKey(cx, cz));
        if (old != null) { pending.remove(old); tracked.remove(old); }
    }
    public void clear() { requireOwner(); chunks.clear(); pending.clear(); tracked.clear(); }

    /** Replay captured dynamic positions after registration/readiness. A worker may
     * have claimed the first capture's notifications before the chunk was registered.
     * No second world/TileEntity scan is needed. */
    public void recheckLoadedChunk(int cx,int cz,WorldLightManager manager) {
        requireOwner();
        Entry entry=chunks.get(CoordinateUtils.getChunkKey(cx,cz));
        if(entry==null)return;
        for(int key:entry.dynamic.toIntArray())
            manager.queueSampledBlockChange((cx<<4)+(key&15),key>>8,(cz<<4)+(key>>>4&15));
    }

    public void load(Chunk chunk, WorldLightManager manager) {
        requireOwner(); unload(chunk.x, chunk.z);
        Entry entry = new Entry(chunk);
        chunks.put(CoordinateUtils.getChunkKey(chunk.x, chunk.z), entry);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        var sections = chunk.getBlockStorageArray();
        boolean[] scanned = new boolean[height.getTotalSections()];
        int[] fluidPositions = FluidLightBridge.LOADED ? FluidLightBridge.fluidPositions(chunk, height.getMinSection(), height.getMaxSection()) : new int[0];
        for (int sectionY = height.getMinSection(); sectionY <= height.getMaxSection(); sectionY++) {
            int index = height.getStorageIndex(sectionY);
            var section = index < 0 || index >= sections.length ? null : sections[index];
            boolean dynamicBlocks = section != null && (!(section.getData() instanceof RgbFilterPalette palette) || palette.pulsar$mayHaveDynamicRgb());
            if (!dynamicBlocks && fluidPositions != null) continue;
            if (index >= 0 && index < scanned.length) scanned[index] = true;
            // Only worlds containing positional APIs allocate this manager. Static-only worlds
            // perform neither this scan nor the per-tick dynamic sampling below.
            for (int ly = 0; ly < 16; ly++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int y = sectionY * 16 + ly;
                int changes = capture(entry, x, y, z, pos);
                if (changes != 0) manager.queueSampledBlockChange((chunk.x << 4) + x, y, (chunk.z << 4) + z);
            }
        }
        if (fluidPositions != null) for (int key : fluidPositions) {
            int y = key >> 8; if (!height.containsBlockY(y)) continue;
            int slot = height.getStorageIndex(y >> 4);
            if (slot >= 0 && slot < scanned.length && scanned[slot]) continue;
            int x = key & 15, z = key >>> 4 & 15;
            if (capture(entry, x, y, z, pos) != 0) manager.queueSampledBlockChange((chunk.x << 4) + x, y, (chunk.z << 4) + z);
        }
        if (!entry.dynamic.isEmpty()) tracked.add(entry);
    }

    public void flush(WorldLightManager manager) {
        requireOwner();
        if (tracked.isEmpty() && pending.isEmpty()) return;
        for (Entry entry : new HashSet<>(tracked)) {
            for (int key : entry.dynamic.toIntArray()) entry.snapshot.request(key);
            if (entry.snapshot.hasPending()) pending.add(entry);
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (Entry entry : new HashSet<>(pending)) {
            pending.remove(entry);
            if (chunks.get(CoordinateUtils.getChunkKey(entry.chunk.x, entry.chunk.z)) != entry) continue;
            for (int key : entry.snapshot.takePending()) {
                int x = key & 15, z = key >>> 4 & 15, y = key >> 8;
                int changes = capture(entry, x, y, z, pos);
                if (changes != 0) manager.queueSampledRgbChange((entry.chunk.x << 4) + x, y,
                        (entry.chunk.z << 4) + z, changes);
            }
            if (entry.dynamic.isEmpty()) tracked.remove(entry); else tracked.add(entry);
            if (entry.snapshot.hasPending()) pending.add(entry);
        }
    }

    private int capture(Entry entry, int x, int y, int z, BlockPos.MutableBlockPos pos) {
        int wx = (entry.chunk.x << 4) + (x & 15), wz = (entry.chunk.z << 4) + (z & 15);
        IBlockState block = entry.chunk.getBlockState(x, y, z);
        IBlockState fluid = FluidLightBridge.LOADED ? FluidLightBridge.stateAt(entry.chunk, wx, y, wz) : null;
        if (!dynamic(block)) block = null;
        if (fluid != null && !dynamic(fluid)) fluid = null;
        pos.setPos(wx, y, wz);
        int blockProperties = block == null ? 0 : sample(block, pos);
        int fluidProperties = fluid == null ? 0 : sample(fluid, pos);
        int key = key(x, y, z);
        if (block != null || fluid != null) entry.dynamic.add(key); else entry.dynamic.remove(key);
        return entry.snapshot.publishChanges(key, block, blockProperties, fluid, fluidProperties, 0xFFF);
    }
    private int sample(IBlockState state, BlockPos pos) {
        int properties = RgbStateProperties.of(state);
        if ((properties & RgbStateProperties.DYNAMIC_SOURCE) != 0) {
            int color = BlockLightColors.checkedColor(((PositionalColoredLightSource) state.getBlock())
                    .getColoredLightEmission(world, pos, state));
            properties = properties & ~0xFFF | color;
        }
        if ((properties & RgbStateProperties.DYNAMIC_FILTER) != 0) {
            int filter = 0xFFF ^ BlockLightColors.checkedColor(((PositionalColoredTranslucency) state.getBlock())
                    .getColoredTransmittance(world, pos, state));
            properties = properties & ~(0xFFF << 12) | filter << 12;
        }
        return properties;
    }
}
