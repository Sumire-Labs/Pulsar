package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.compat.FluidLightBridge;
import com.sumirelabs.pulsar.config.PulsarConfig;
import com.sumirelabs.pulsar.light.engine.LightInfo;
import com.sumirelabs.pulsar.util.CoordinateUtils;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import it.unimi.dsi.fastutil.ints.IntIterator;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Captures contextual emission/opacity on the world thread, including optional
 * fluid states. No World or TileEntity callback is made by a lighting worker.
 * Chunks are scanned once on load; subsequent refreshes visit requested cells
 * and tracked contextual tile sources, never all loaded chunks' blocks every tick.
 */
public final class ContextualLightManager {
    // Owner-thread monotonic flag: worlds which never had samples need no neighbour scan.
    private boolean hasContextualSources;
    private static final class Entry {
        final Chunk chunk;
        final ContextualLightSnapshot<IBlockState> snapshot;
        final Set<BlockPos> tracked = new HashSet<>();
        boolean active = true;
        Entry(Chunk chunk, ContextualLightSnapshot<IBlockState> snapshot) {
            this.chunk = chunk; this.snapshot = snapshot;
        }
    }

    private record TrackedSource(Entry entry, TileEntity tile) {}

    @FunctionalInterface
    interface LightChanges { void changed(int x, int y, int z, int lanes); }

    private final World world;
    private final WorldHeightContext height;
    private final Thread owner = Thread.currentThread();
    private final ConcurrentHashMap<Long, Entry> chunks = new ConcurrentHashMap<>();
    private final Set<Entry> pendingChunks = ConcurrentHashMap.newKeySet();
    private final TrackedLightSources<BlockPos, TrackedSource> trackedSources = new TrackedLightSources<>();

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
            this.untrack(previous);
        }
        for (final BlockPos pos : new HashSet<>(chunk.getTileEntityMap().keySet())) this.track(entry, pos);
        this.discoverLoadedNeighbours(entry);
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
            this.untrack(entry);
        }
    }

    private void untrack(final Entry entry) {
        entry.active = false;
        for (BlockPos pos : entry.tracked) {
            TrackedSource source = this.trackedSources.get(pos);
            if (source != null && source.entry == entry) this.trackedSources.update(pos, null);
        }
        entry.tracked.clear();
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
        this.requestNeighbours(x, y, z);
    }

    private void requestNeighbours(final int x, final int y, final int z) {
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
        this.flushSamples((x, y, z, lanes) -> manager.queueSampledBlockChange(x, y, z, lanes));
    }

    void flushSamples(final LightChanges changes) {
        this.requireOwner();
        if (!this.pendingChunks.isEmpty()) this.flushPending(changes);
        this.pollTracked(changes);
    }

    private void flushPending(final LightChanges changes) {
        // Explicit block/TE changes and worker misses must not wait behind the
        // bounded fallback poll. Reentrant requests stay queued for next tick.
        for (final Entry entry : new HashSet<>(this.pendingChunks)) {
            this.pendingChunks.remove(entry);
            if (this.chunks.get(CoordinateUtils.mixChunkKey(
                    CoordinateUtils.getChunkKey(entry.chunk.x, entry.chunk.z))) != entry) continue;
            // Local to this batch: reentrant callbacks cannot overwrite a
            // different flush's position. Reuse for capture and tile lookups.
            final BlockPos.MutableBlockPos samplePos = new BlockPos.MutableBlockPos();
            final IntIterator pending = entry.snapshot.takePendingPositions().iterator();
            while (pending.hasNext()) {
                final int key = pending.nextInt();
                final int x = key & 15;
                final int z = (key >>> 4) & 15;
                final int y = key >> 8;
                final int lanes = this.capture(entry, x, y, z, false, samplePos);
                if (!entry.active) break;
                samplePos.setPos((entry.chunk.x << 4) + x, y, (entry.chunk.z << 4) + z);
                this.track(entry, samplePos);
                if (lanes != 0) {
                    // Publish before enqueueing; bypass request() to avoid a refresh loop.
                    changes.changed((entry.chunk.x << 4) + x, y, (entry.chunk.z << 4) + z, lanes);
                }
            }
        }
    }

    private void pollTracked(final LightChanges changes) {
        if (!PulsarConfig.features.trackTileEntityLight || this.trackedSources.isEmpty()) return;
        final int limit = Math.min(this.trackedSources.size(), Math.max(1, PulsarConfig.performance.tileEntityLightSamplesPerTick));
        final long budget = Math.max(1L, PulsarConfig.performance.tileEntityLightBudgetMicros) * 1000L;
        final long start = System.nanoTime();
        final BlockPos.MutableBlockPos samplePos = new BlockPos.MutableBlockPos();
        final BiConsumer<BlockPos, TrackedSource> sample = (pos, source) -> {
            final Entry entry = source.entry;
            final boolean sameTile = source.tile != null && !source.tile.isInvalid()
                    && entry.chunk.getTileEntityMap().get(pos) == source.tile;
            final int lanes = this.capture(entry, pos.getX() & 15, pos.getY(), pos.getZ() & 15, false, samplePos);
            // MOD callbacks may unload/replace this chunk reentrantly.
            if (!entry.active) return;
            if (!sameTile || lanes != 0) this.track(entry, pos);
            if (lanes != 0) {
                changes.changed(pos.getX(), pos.getY(), pos.getZ(), lanes);
                // Neighbour-dependent cells are polled independently too.
                // An observed change additionally triggers prompt invalidation.
                this.requestNeighbours(pos.getX(), pos.getY(), pos.getZ());
            }
        };
        // One world-wide source cursor gives dense and sparse chunks the same
        // revisit interval. Never scan exhausted chunks or restart at chunk 0.
        for (int samples = 0; samples < limit && !this.trackedSources.isEmpty()
                && System.nanoTime() - start < budget; samples++) {
            this.trackedSources.sampleNext(sample);
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
        TileEntity tile = entry.chunk.getTileEntityMap().get(pos);
        if (tile != null && tile.isInvalid()) tile = null;
        final boolean contextual = this.height.containsBlockY(pos.getY())
                && entry.snapshot.contains(pack(pos.getX(), pos.getY(), pos.getZ()))
                && (tile != null || this.hasTrackedNeighbour(pos));
        // Mutable lookup keys are safe for removal; only retained keys must
        // be immutable. An unchanged source needs no replacement key.
        if (contextual) {
            final TrackedSource previous = this.trackedSources.get(pos);
            if (previous == null || previous.entry != entry || previous.tile != tile) {
                final BlockPos retained = pos.toImmutable();
                entry.tracked.add(retained);
                this.trackedSources.update(retained, new TrackedSource(entry, tile));
                if (tile != null) this.discoverDependentNeighbours(retained);
            }
        } else {
            entry.tracked.remove(pos);
            final TrackedSource previous = this.trackedSources.get(pos);
            if (previous != null && previous.entry == entry) this.trackedSources.update(pos, null);
        }
    }

    private boolean hasTrackedNeighbour(final BlockPos pos) {
        for (final EnumFacing face : EnumFacing.VALUES) {
            final TrackedSource neighbour = this.trackedSources.get(pos.offset(face));
            if (neighbour != null && neighbour.tile != null && !neighbour.tile.isInvalid()) return true;
        }
        return false;
    }

    private void discoverDependentNeighbours(final BlockPos pos) {
        for (final EnumFacing face : EnumFacing.VALUES) {
            final BlockPos adjacent = pos.offset(face);
            final Entry entry = this.chunks.get(CoordinateUtils.mixChunkKey(
                    CoordinateUtils.getChunkKey(adjacent.getX() >> 4, adjacent.getZ() >> 4)));
            // Direct TE sources are installed by their own lifecycle callbacks;
            // do not recursively walk chains of TE blocks while registering.
            if (entry != null && entry.snapshot.contains(pack(adjacent.getX(), adjacent.getY(), adjacent.getZ()))
                    && entry.chunk.getTileEntityMap().get(adjacent) == null) this.track(entry, adjacent);
        }
    }

    private void discoverLoadedNeighbours(final Entry entry) {
        if (entry.snapshot.isEmpty()) return;
        // Existing TEs just outside this chunk may depend on cells captured
        // during this load. Inspect neighbour sources, not every block again.
        for (final EnumFacing face : EnumFacing.HORIZONTALS) {
            final Entry neighbour = this.chunks.get(CoordinateUtils.mixChunkKey(
                    CoordinateUtils.getChunkKey(entry.chunk.x + face.getXOffset(), entry.chunk.z + face.getZOffset())));
            if (neighbour == null) continue;
            for (final BlockPos pos : neighbour.tracked) {
                final BlockPos adjacent = pos.offset(face.getOpposite());
                if ((adjacent.getX() >> 4) != entry.chunk.x || (adjacent.getZ() >> 4) != entry.chunk.z) continue;
                final TrackedSource source = this.trackedSources.get(pos);
                if (source != null && source.tile != null
                        && entry.snapshot.contains(pack(adjacent.getX(), adjacent.getY(), adjacent.getZ()))
                        && entry.chunk.getTileEntityMap().get(adjacent) == null) this.track(entry, adjacent);
            }
        }
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
