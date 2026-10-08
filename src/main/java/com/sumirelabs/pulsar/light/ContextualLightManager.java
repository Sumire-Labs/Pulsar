package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.compat.FluidLightBridge;
import com.sumirelabs.pulsar.config.PulsarConfig;
import com.sumirelabs.pulsar.light.engine.LightInfo;
import com.sumirelabs.pulsar.util.CoordinateUtils;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.tileentity.TileEntity;
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
 * and tracked contextual tile sources, never all loaded chunks' blocks every tick.
 */
public final class ContextualLightManager {
    // Owner-thread monotonic flag: worlds which never had samples need no neighbour scan.
    private boolean hasContextualSources;
    private record Entry(Chunk chunk, ContextualLightSnapshot<IBlockState> snapshot,
                         TrackedLightSources<BlockPos, TileEntity> tracked) {}

    private final World world;
    private final WorldHeightContext height;
    private final Thread owner = Thread.currentThread();
    private final ConcurrentHashMap<Long, Entry> chunks = new ConcurrentHashMap<>();
    private final Set<Entry> pendingChunks = ConcurrentHashMap.newKeySet();
    private final Set<Entry> trackedChunks = new HashSet<>();

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
        final Entry entry = new Entry(chunk, new ContextualLightSnapshot<>(), new TrackedLightSources<>());
        // Local to this load: callbacks can reenter lighting with their own position.
        final BlockPos.MutableBlockPos samplePos = new BlockPos.MutableBlockPos();
        // Publish only after capture, before the chunk becomes worker-visible.
        final ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();
        final int[] fluidPositions = FluidLightBridge.LOADED
                ? FluidLightBridge.fluidPositions(chunk, this.height.getMinSection(), this.height.getMaxSection())
                : null;
        final boolean[] scannedSections = fluidPositions == null && FluidLightBridge.LOADED
                ? null : new boolean[sections.length];
        for (int sectionY = this.height.getMinSection(); sectionY <= this.height.getMaxSection(); sectionY++) {
            final int index = this.height.getStorageIndex(sectionY);
            final ExtendedBlockStorage section = index >= 0 && index < sections.length ? sections[index] : null;
            final boolean hasBlocks = section != null && !section.isEmpty();
            boolean contextualBlocks = false;
            if (hasBlocks) {
                // Palette classification is cheap relative to visiting 4096 cells.
                // Contextual states need position-specific callbacks; static states do not.
                final Object data = section.getData();
                final int paletteFlags = data instanceof ContextualLightPalette
                        ? ((ContextualLightPalette) data).pulsar$lightPaletteFlags()
                        : ContextualLightPalette.CONSERVATIVE_FLAGS;
                contextualBlocks = (paletteFlags & ContextualLightPalette.CONTEXT_MASK) != 0;
            }
            final boolean fullScan = shouldScanSection(hasBlocks, contextualBlocks,
                    FluidLightBridge.LOADED, fluidPositions != null);
            if (!fullScan) continue;
            if (scannedSections != null && index >= 0 && index < scannedSections.length) {
                scannedSections[index] = true;
            }
            for (int y = sectionY << 4; y < (sectionY + 1) << 4; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) this.capture(entry, x, y, z, true, samplePos);
                }
            }
        }
        if (fluidPositions != null) {
            for (final int key : fluidPositions) {
                final int y = key >> 8;
                if (!this.height.containsBlockY(y)) continue;
                final int sectionIndex = this.height.getStorageIndex(y >> 4);
                if (scannedSections != null && sectionIndex >= 0 && sectionIndex < scannedSections.length
                        && scannedSections[sectionIndex]) continue;
                this.capture(entry, key & 15, y, (key >>> 4) & 15, true, samplePos);
            }
        }
        final Entry previous = this.chunks.put(
                CoordinateUtils.mixChunkKey(CoordinateUtils.getChunkKey(chunk.x, chunk.z)), entry);
        if (previous != null) {
            this.pendingChunks.remove(previous);
            this.trackedChunks.remove(previous);
        }
        for (final BlockPos pos : new HashSet<>(chunk.getTileEntityMap().keySet())) this.track(entry, pos);
        if (!entry.tracked.isEmpty()) this.trackedChunks.add(entry);
    }

    static boolean shouldScanSection(final boolean hasBlocks, final boolean contextualBlocks,
                                     final boolean fluidlogged, final boolean fluidIndexAvailable) {
        return (hasBlocks && contextualBlocks) || (fluidlogged && !fluidIndexAvailable);
    }

    public void unload(final int x, final int z) {
        final Entry entry = this.chunks.remove(
                CoordinateUtils.mixChunkKey(CoordinateUtils.getChunkKey(x, z)));
        if (entry != null) {
            this.pendingChunks.remove(entry);
            this.trackedChunks.remove(entry);
        }
    }

    public int read(final int info, final IBlockState state, final int x, final int y, final int z) {
        final Entry entry = this.chunks.get(CoordinateUtils.mixChunkKey(
                CoordinateUtils.getChunkKey(x >> 4, z >> 4)));
        if (entry == null || !this.height.containsBlockY(y)) return info;
        final int result = entry.snapshot.read(pack(x, y, z), state, info);
        if (entry.snapshot.hasPending()) this.pendingChunks.add(entry);
        return result;
    }

    /** Called for checkLight and successful block changes, after TE setup settles at tick end. */
    public void request(final int x, final int y, final int z) {
        this.requestCell(x, y, z, true);
        if (this.isOwnerThread() && !FluidLightBridge.LOADED && !this.hasContextualSources) return;
        this.requestCell(x - 1, y, z, false);
        this.requestCell(x + 1, y, z, false);
        this.requestCell(x, y - 1, z, false);
        this.requestCell(x, y + 1, z, false);
        this.requestCell(x, y, z - 1, false);
        this.requestCell(x, y, z + 1, false);
    }

    private void requestCell(final int x, final int y, final int z, final boolean discover) {
        if (!this.height.containsBlockY(y)) return;
        final Entry entry = this.chunks.get(CoordinateUtils.mixChunkKey(
                CoordinateUtils.getChunkKey(x >> 4, z >> 4)));
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
        final Entry entry = this.chunks.get(CoordinateUtils.mixChunkKey(CoordinateUtils.getChunkKey(x, z)));
        return entry != null && entry.snapshot.hasPending();
    }

    public void flush(final WorldLightManager manager) {
        this.requireOwner();
        for (final Entry entry : PulsarConfig.features.trackTileEntityLight && !this.trackedChunks.isEmpty()
                ? new HashSet<>(this.trackedChunks) : java.util.Collections.<Entry>emptySet()) {
            entry.tracked.tick((pos, tile) -> !tile.isInvalid()
                    && entry.chunk.getTileEntityMap().get(pos) == tile,
                    pos -> this.request(pos.getX(), pos.getY(), pos.getZ()));
            if (entry.tracked.isEmpty()) this.trackedChunks.remove(entry);
        }
        if (this.pendingChunks.isEmpty()) return;
        for (final Entry entry : new HashSet<>(this.pendingChunks)) {
            this.pendingChunks.remove(entry);
            if (this.chunks.get(CoordinateUtils.mixChunkKey(
                    CoordinateUtils.getChunkKey(entry.chunk.x, entry.chunk.z))) != entry) continue;
            // Local to this batch: reentrant callbacks cannot overwrite a
            // different flush's position. Reuse for capture and tile lookups.
            final BlockPos.MutableBlockPos samplePos = new BlockPos.MutableBlockPos();
            for (final int key : entry.snapshot.takePending()) {
                final int x = key & 15;
                final int z = (key >>> 4) & 15;
                final int y = key >> 8;
                final int changes = this.capture(entry, x, y, z, false, samplePos);
                samplePos.setPos((entry.chunk.x << 4) + x, y, (entry.chunk.z << 4) + z);
                this.track(entry, samplePos);
                if (changes != 0) {
                    // Publish before enqueueing; bypass request() to avoid a refresh loop.
                    manager.queueSampledBlockChange((entry.chunk.x << 4) + x, y, (entry.chunk.z << 4) + z, changes);
                }
            }
        }
    }

    public void requestBlockChange(final IBlockState previous, final IBlockState next,
                                    final int x, final int y, final int z) {
        if (this.isOwnerThread() && !FluidLightBridge.LOADED && !this.hasContextualSources
                && !LightInfo.hasContextualValues(LightInfo.of(previous))
                && !LightInfo.hasContextualValues(LightInfo.of(next))) return;
        this.request(x, y, z);
    }

    /** Same neighbourhood invalidation as repeated request(), without discovering static air. */
    public void requestVerticalRange(final int x, final int z, final int firstY, final int lastY) {
        final int minY = Math.max(this.height.getMinBlockY(), Math.min(firstY, lastY));
        final int maxY = Math.min(this.height.getMaxBlockY(), Math.max(firstY, lastY));
        if (minY > maxY) return;
        if (!this.isOwnerThread() || FluidLightBridge.LOADED) {
            // Unknown off-thread mutations and newly stored contextual fluids retain discovery.
            for (int y = minY;; y++) {
                this.request(x, y, z);
                if (y == maxY) break;
            }
            return;
        }
        this.requestStoredColumn(x, z,
                minY > this.height.getMinBlockY() ? minY - 1 : minY,
                maxY < this.height.getMaxBlockY() ? maxY + 1 : maxY);
        this.requestStoredColumn(x - 1, z, minY, maxY);
        this.requestStoredColumn(x + 1, z, minY, maxY);
        this.requestStoredColumn(x, z - 1, minY, maxY);
        this.requestStoredColumn(x, z + 1, minY, maxY);
    }

    private void requestStoredColumn(final int x, final int z, final int minY, final int maxY) {
        final Entry entry = this.chunks.get(CoordinateUtils.mixChunkKey(
                CoordinateUtils.getChunkKey(x >> 4, z >> 4)));
        if (entry == null) return;
        entry.snapshot.requestColumn(x, z, minY, maxY);
        if (entry.snapshot.hasPending()) this.pendingChunks.add(entry);
    }

    /** Called after tile installation/removal; never calls World.getTileEntity. */
    public void tileEntityChanged(final BlockPos pos) {
        final Entry entry = this.chunks.get(CoordinateUtils.mixChunkKey(
                CoordinateUtils.getChunkKey(pos.getX() >> 4, pos.getZ() >> 4)));
        if (entry == null || !this.height.containsBlockY(pos.getY())) return;
        entry.snapshot.request(pack(pos.getX(), pos.getY(), pos.getZ()));
        this.pendingChunks.add(entry);
    }

    private void track(final Entry entry, final BlockPos pos) {
        final TileEntity tile = entry.chunk.getTileEntityMap().get(pos);
        final boolean contextual = this.height.containsBlockY(pos.getY()) && tile != null && !tile.isInvalid()
                && LightInfo.hasContextualValues(LightInfo.of(entry.chunk.getBlockState(pos)));
        // Mutable lookup keys are safe for removal; only retained keys must
        // be immutable. An unchanged source needs no replacement key.
        if (contextual) {
            if (!entry.tracked.matches(pos, tile)) entry.tracked.update(pos.toImmutable(), tile);
        } else {
            entry.tracked.update(pos, null);
        }
        if (entry.tracked.isEmpty()) this.trackedChunks.remove(entry);
        else this.trackedChunks.add(entry);
    }

    private int capture(final Entry entry, final int x, final int y, final int z,
                        final boolean initial, final BlockPos.MutableBlockPos samplePos) {
        final int worldX = (entry.chunk.x << 4) + (x & 15);
        final int worldZ = (entry.chunk.z << 4) + (z & 15);
        IBlockState block = entry.chunk.getBlockState(x, y, z);
        IBlockState fluid = FluidLightBridge.LOADED ? FluidLightBridge.stateAt(entry.chunk, worldX, y, worldZ) : null;
        int blockInfo = LightInfo.of(block);
        int fluidInfo = fluid == null ? 0 : LightInfo.of(fluid);
        if (!LightInfo.hasContextualValues(blockInfo)) block = null;
        if (!LightInfo.hasContextualValues(fluidInfo)) fluid = null;
        if (block != null || fluid != null) {
            this.hasContextualSources = true;
            if (block != null) blockInfo = LightInfo.resolveContextual(blockInfo, block, this.world, samplePos, worldX, y, worldZ);
            if (fluid != null) fluidInfo = LightInfo.resolveContextual(fluidInfo, fluid, this.world, samplePos, worldX, y, worldZ);
        }
        final int key = pack(x, y, z);
        if (initial) {
            entry.snapshot.initialize(key, block, blockInfo, fluid, fluidInfo);
            return 0;
        }
        return entry.snapshot.publishChanges(key, block, blockInfo, fluid, fluidInfo,
                LightInfo.OPACITY_MASK << LightInfo.EMISSION_SHIFT);
    }

    /** Signed Y, independent of storage order and vanilla's 0..255 limits. */
    static int pack(final int x, final int y, final int z) {
        return (y << 8) | ((z & 15) << 4) | (x & 15);
    }
}
