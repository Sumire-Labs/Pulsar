package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.compat.FluidLightBridge;
import com.sumirelabs.pulsar.light.engine.LightInfo;
import com.sumirelabs.pulsar.util.CoordinateUtils;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Captures contextual emission/opacity on the world thread, including optional
 * fluid states. No World or TileEntity callback is made by a lighting worker.
 * Chunks are scanned once on load; subsequent refreshes visit requested cells
 * and their immediate neighbours, never all loaded chunks' blocks every tick.
 */
public final class ContextualLightManager {
    private record Entry(Chunk chunk, ContextualLightSnapshot<IBlockState> snapshot) {}

    private final World world;
    private final WorldHeightContext height;
    private final Thread owner = Thread.currentThread();
    private final ConcurrentHashMap<Long, Entry> chunks = new ConcurrentHashMap<>();
    private final Set<Entry> pendingChunks = ConcurrentHashMap.newKeySet();

    public ContextualLightManager(final World world, final WorldHeightContext height) {
        this.world = world;
        this.height = height;
    }

    public boolean isOwnerThread() {
        return Thread.currentThread() == this.owner;
    }

    private void requireOwner() {
        if (!this.isOwnerThread()) throw new IllegalStateException("Light samples require the world thread");
    }

    public void load(final Chunk chunk) {
        this.requireOwner();
        final Entry entry = new Entry(chunk, new ContextualLightSnapshot<>());
        // Publish only after capture, before the chunk becomes worker-visible.
        final ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();
        for (int sectionY = this.height.getMinSection(); sectionY <= this.height.getMaxSection(); sectionY++) {
            final int index = this.height.getStorageIndex(sectionY);
            if (!FluidLightBridge.LOADED && (index >= sections.length || sections[index] == null
                    || sections[index].isEmpty())) continue;
            for (int y = sectionY << 4; y < (sectionY + 1) << 4; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) this.capture(entry, x, y, z);
                }
            }
        }
        final Entry previous = this.chunks.put(CoordinateUtils.getChunkKey(chunk.x, chunk.z), entry);
        if (previous != null) this.pendingChunks.remove(previous);
    }

    public void unload(final int x, final int z) {
        final Entry entry = this.chunks.remove(CoordinateUtils.getChunkKey(x, z));
        if (entry != null) this.pendingChunks.remove(entry);
    }

    public int read(final int info, final IBlockState state, final int x, final int y, final int z) {
        final Entry entry = this.chunks.get(CoordinateUtils.getChunkKey(x >> 4, z >> 4));
        if (entry == null || !this.height.containsBlockY(y)) return info;
        final int result = entry.snapshot.read(pack(x, y, z), state, info);
        if (entry.snapshot.hasPending()) this.pendingChunks.add(entry);
        return result;
    }

    /** Called for checkLight and successful block changes, after TE setup settles at tick end. */
    public void request(final int x, final int y, final int z) {
        this.requestCell(x, y, z, true);
        this.requestCell(x - 1, y, z, false);
        this.requestCell(x + 1, y, z, false);
        this.requestCell(x, y - 1, z, false);
        this.requestCell(x, y + 1, z, false);
        this.requestCell(x, y, z - 1, false);
        this.requestCell(x, y, z + 1, false);
    }

    private void requestCell(final int x, final int y, final int z, final boolean discover) {
        if (!this.height.containsBlockY(y)) return;
        final Entry entry = this.chunks.get(CoordinateUtils.getChunkKey(x >> 4, z >> 4));
        if (entry == null) return;
        final int key = pack(x, y, z);
        // Off-thread requests must not inspect live world state.
        if (!this.isOwnerThread() || entry.snapshot.contains(key)
                || (discover && (FluidLightBridge.LOADED
                || LightInfo.hasContextualValues(LightInfo.of(entry.chunk.getBlockState(x, y, z)))))) {
            entry.snapshot.request(key);
            this.pendingChunks.add(entry);
        }
    }

    public boolean hasPending(final int x, final int z) {
        final Entry entry = this.chunks.get(CoordinateUtils.getChunkKey(x, z));
        return entry != null && entry.snapshot.hasPending();
    }

    public void flush(final WorldLightManager manager) {
        this.requireOwner();
        if (this.pendingChunks.isEmpty()) return;
        for (final Entry entry : new HashSet<>(this.pendingChunks)) {
            this.pendingChunks.remove(entry);
            if (this.chunks.get(CoordinateUtils.getChunkKey(entry.chunk.x, entry.chunk.z)) != entry) continue;
            for (final int key : entry.snapshot.takePending()) {
                final int x = key & 15;
                final int z = (key >>> 4) & 15;
                final int y = key >> 8;
                this.capture(entry, x, y, z);
                // Publish before enqueueing; bypass request() to avoid a refresh loop.
                manager.queueSampledBlockChange((entry.chunk.x << 4) + x, y, (entry.chunk.z << 4) + z);
            }
        }
    }

    private void capture(final Entry entry, final int x, final int y, final int z) {
        final int worldX = (entry.chunk.x << 4) + (x & 15);
        final int worldZ = (entry.chunk.z << 4) + (z & 15);
        IBlockState block = entry.chunk.getBlockState(x, y, z);
        IBlockState fluid = FluidLightBridge.LOADED ? FluidLightBridge.stateAt(entry.chunk, worldX, y, worldZ) : null;
        int blockInfo = LightInfo.of(block);
        int fluidInfo = fluid == null ? 0 : LightInfo.of(fluid);
        if (!LightInfo.hasContextualValues(blockInfo)) block = null;
        if (!LightInfo.hasContextualValues(fluidInfo)) fluid = null;
        if (block != null || fluid != null) {
            final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            if (block != null) blockInfo = LightInfo.resolveContextual(blockInfo, block, this.world, pos, worldX, y, worldZ);
            if (fluid != null) fluidInfo = LightInfo.resolveContextual(fluidInfo, fluid, this.world, pos, worldX, y, worldZ);
        }
        final int key = pack(x, y, z);
        if (block != null || fluid != null || entry.snapshot.contains(key)) {
            entry.snapshot.publish(key, block, blockInfo, fluid, fluidInfo);
        }
    }

    /** Signed Y, independent of storage order and vanilla's 0..255 limits. */
    static int pack(final int x, final int y, final int z) {
        return (y << 8) | ((z & 15) << 4) | (x & 15);
    }
}
