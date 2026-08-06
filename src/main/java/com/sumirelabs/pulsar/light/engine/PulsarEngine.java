package com.sumirelabs.pulsar.light.engine;

import com.sumirelabs.pulsar.Pulsar;
import com.sumirelabs.pulsar.light.LightStats;
import com.sumirelabs.pulsar.light.SWMRNibbleArray;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import com.sumirelabs.pulsar.util.WorldUtil;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.block.state.IBlockState;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Abstract base for Pulsar's BFS-based scalar lighting engines. Ported from
 * SuperNova {@code SupernovaEngine} (1.7.10) with the RGB code paths removed
 * and the block-id-based caches replaced with {@link IBlockState} caches.
 *
 * <p>Queue entry bit layout (long):
 * <pre>
 *   bits  0..5  : X (6 bits, [-32, 31] relative to cache center)
 *   bits  6..11 : Z (6 bits)
 *   bits 12..27 : Y (16 bits, signed)
 *   bits 28..31 : light level (4 bits, scalar 0–15)
 *   bits 32..37 : direction bitset (6 bits)
 *   bits 38..60 : unused
 *   bit  61     : FLAG_WRITE_LEVEL
 *   bit  62     : FLAG_RECHECK_LEVEL
 *   bit  63     : FLAG_HAS_SIDED_TRANSPARENT_BLOCKS
 * </pre>
 */
public abstract class PulsarEngine extends LightEngineCache {

    private final LightSectionProcessor sectionProcessor;
    protected final boolean skylightPropagator;

    protected static final AxisDirection[] AXIS_DIRECTIONS = AxisDirection.values();
    protected static final AxisDirection[] ONLY_HORIZONTAL_DIRECTIONS = new AxisDirection[]{
            AxisDirection.POSITIVE_X, AxisDirection.NEGATIVE_X,
            AxisDirection.POSITIVE_Z, AxisDirection.NEGATIVE_Z
    };

    protected enum AxisDirection {
        POSITIVE_X(1, 0, 0),
        NEGATIVE_X(-1, 0, 0),
        POSITIVE_Z(0, 0, 1),
        NEGATIVE_Z(0, 0, -1),
        POSITIVE_Y(0, 1, 0),
        NEGATIVE_Y(0, -1, 0);

        static {
            POSITIVE_X.opposite = NEGATIVE_X;
            NEGATIVE_X.opposite = POSITIVE_X;
            POSITIVE_Z.opposite = NEGATIVE_Z;
            NEGATIVE_Z.opposite = POSITIVE_Z;
            POSITIVE_Y.opposite = NEGATIVE_Y;
            NEGATIVE_Y.opposite = POSITIVE_Y;
        }

        protected AxisDirection opposite;

        protected final int x;
        protected final int y;
        protected final int z;
        protected final int oppositeOrdinal;
        protected final long everythingButTheOppositeDirection;
        protected final long everythingButThisDirection;

        AxisDirection(final int x, final int y, final int z) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.oppositeOrdinal = this.ordinal() ^ 1;
            final int allBits = (1 << 6) - 1;
            this.everythingButTheOppositeDirection = allBits ^ (1L << (this.ordinal() ^ 1));
            this.everythingButThisDirection = allBits ^ (1L << this.ordinal());
        }

        protected AxisDirection getOpposite() {
            return this.opposite;
        }
    }

    // Pool for int[4096] arrays — avoids allocation churn during BFS
    protected static final ThreadLocal<ArrayDeque<int[]>> PACKED_ARRAY_POOL = ThreadLocal.withInitial(ArrayDeque::new);

    protected static int[] acquirePackedArray() {
        final int[] pooled = PACKED_ARRAY_POOL.get().pollFirst();
        return pooled != null ? pooled : new int[4096];
    }

    protected static void releasePackedArray(final int[] arr) {
        if (arr != null) {
            PACKED_ARRAY_POOL.get().addFirst(arr);
        }
    }

    // Diagnostic counters — accumulated across propagateBlockChanges calls
    public int lastBfsIncreaseTotal;
    public int lastBfsDecreaseTotal;
    public int lastPositionsProcessed;
    // Queue entry constants — see class javadoc
    protected static final int COORD_X_BITS = 6;
    protected static final int COORD_Z_BITS = 6;
    protected static final int COORD_Y_BITS = 16;
    protected static final int COORD_Y_MASK = (1 << COORD_Y_BITS) - 1;
    protected static final int LIGHT_LEVEL_SHIFT = COORD_X_BITS + COORD_Z_BITS + COORD_Y_BITS; // 28
    protected static final int DIRECTION_SHIFT = LIGHT_LEVEL_SHIFT + 4; // 32
    protected static final long COORD_MASK = (1L << LIGHT_LEVEL_SHIFT) - 1;

    protected static long encodeCoords(final int x, final int z, final int y, final int encodeOffset) {
        return (x + ((long) z << COORD_X_BITS) + ((long) y << (COORD_X_BITS + COORD_Z_BITS)) + encodeOffset) & COORD_MASK;
    }

    protected static long sidedFlag(final IBlockState state) {
        return sidedFlag(LightInfo.of(state));
    }

    protected static long sidedFlag(final int lightInfo) {
        return (lightInfo & LightInfo.REGISTRY) != 0 ? FLAG_HAS_SIDED_TRANSPARENT_BLOCKS : 0L;
    }

    protected PulsarEngine(final boolean skylightPropagator, final World world,
                           final WorldHeightContext heightContext) {
        super(world, heightContext);
        this.skylightPropagator = skylightPropagator;
        this.sectionProcessor = new LightSectionProcessor(this);
    }

    @Override
    public void setStats(final LightStats stats) {
        super.setStats(stats);
    }

    @Override
    protected final void resetTaskState() {
        this.queueOverflowed = false;
        this.queueOverflowWarned = false;
        this.increaseQueueInitialLength = 0;
        this.decreaseQueueInitialLength = 0;
    }

    protected long encodeQueueLevel(final int level) {
        return (level & 0xFL) << LIGHT_LEVEL_SHIFT;
    }

    protected int getDirectionShift() {
        return DIRECTION_SHIFT;
    }

    protected boolean isBelowPropagationThreshold(final int level) {
        return level <= 1;
    }

    public static Boolean[] getEmptySectionsForChunk(final Chunk chunk) {
        final WorldHeightContext heightContext = WorldUtil.getHeightContext(chunk.getWorld());
        final ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();
        final Boolean[] ret = new Boolean[heightContext.getTotalSections()];
        for (int sectionY = heightContext.getMinSection(); sectionY <= heightContext.getMaxSection(); ++sectionY) {
            final int logicalIndex = heightContext.getSectionIndex(sectionY);
            final int storageIndex = heightContext.getStorageIndex(sectionY);
            final ExtendedBlockStorage section = storageIndex >= 0 && storageIndex < sections.length
                    ? sections[storageIndex] : null;
            ret[logicalIndex] = section == null || section.isEmpty() ? Boolean.TRUE : Boolean.FALSE;
        }
        return ret;
    }

    protected abstract void setEmptinessMap(final Chunk chunk, final boolean[] to);

    @Override
    protected abstract boolean[] getEmptinessMap(final Chunk chunk);

    @Override
    protected abstract SWMRNibbleArray[] getNibblesOnChunk(final Chunk chunk);

    @Override
    protected abstract boolean canUseChunk(final Chunk chunk);

    protected abstract void setNibbles(final Chunk chunk, final SWMRNibbleArray[] to);

    protected abstract void initNibble(final int chunkX, final int chunkY, final int chunkZ, final boolean extrude, final boolean initRemovedNibbles);

    protected abstract void setNibbleNull(final int chunkX, final int chunkY, final int chunkZ);

    protected abstract void propagateBlockChanges(final Chunk atChunk, final int blockX, final int blockY, final int blockZ);

    protected abstract void checkBlock(final int worldX, final int worldY, final int worldZ);

    protected abstract int calculateLightValue(final int worldX, final int worldY, final int worldZ, final int expect);

    protected abstract void lightChunk(final Chunk chunk, final boolean needsEdgeChecks);

    public final void blockChanged(final int blockX, final int blockY, final int blockZ) {
        if (!this.heightContext.containsBlockY(blockY)) {
            return;
        }
        final int chunkX = blockX >> 4;
        final int chunkZ = blockZ >> 4;
        this.setupCaches(chunkX * 16 + 7, 128, chunkZ * 16 + 7, true, true);
        try {
            final Chunk chunk = this.getChunkInCache(chunkX, chunkZ);
            if (chunk == null) {
                return;
            }
            this.propagateBlockChanges(chunk, blockX, blockY, blockZ);
            this.updateVisible();
        } finally {
            this.destroyCaches();
        }
    }

    public final void blocksChangedInChunk(final int chunkX, final int chunkZ, final IntOpenHashSet changedPositions, final Boolean[] changedSections) {
        this.lastBfsIncreaseTotal = 0;
        this.lastBfsDecreaseTotal = 0;
        this.lastPositionsProcessed = 0;
        this.setupCaches(chunkX * 16 + 7, 128, chunkZ * 16 + 7, true, true);
        try {
            final Chunk chunk = this.getChunkInCache(chunkX, chunkZ);
            if (chunk == null) {
                return;
            }
            // 1. Section changes first (creates/removes nibbles)
            final Boolean[] effectiveSectionChanges = this.reconcileSectionChanges(
                    chunk, changedPositions, changedSections);
            if (effectiveSectionChanges != null) {
                final boolean[] ret = this.handleEmptySectionChanges(chunk, effectiveSectionChanges, false);
                if (ret != null) {
                    this.setEmptinessMap(chunk, ret);
                }
            }
            // 2. Block changes (uses now-updated nibbles)
            if (changedPositions != null && !changedPositions.isEmpty()) {
                this.processBlockPositionChanges(chunk, chunkX, chunkZ, changedPositions);
            }
            this.updateVisible();
        } finally {
            this.destroyCaches();
        }
    }

    /**
     * Reconcile queued section-transition events with the chunk's current
     * storage before processing block changes. Height-extension mods can
     * replace {@code Chunk#setBlockState} with a cancellable HEAD injection,
     * so another mixin's RETURN hook is not a reliable way to observe a newly
     * created or emptied section. Every queued block position gives us a
     * second, ordering-independent opportunity to detect that transition.
     */
    private Boolean[] reconcileSectionChanges(final Chunk chunk,
                                              final IntOpenHashSet changedPositions,
                                              final Boolean[] changedSections) {
        if (changedPositions == null || changedPositions.isEmpty()) {
            return changedSections;
        }

        final int totalSections = this.heightContext.getTotalSections();
        Boolean[] effectiveChanges = changedSections;
        if (effectiveChanges != null && effectiveChanges.length != totalSections) {
            effectiveChanges = Arrays.copyOf(effectiveChanges, totalSections);
        }

        final boolean[] knownEmptiness = this.getEmptinessMap(chunk.x, chunk.z);
        final IntIterator iterator = changedPositions.iterator();
        while (iterator.hasNext()) {
            final int sectionY = (iterator.nextInt() >> 8) >> 4;
            final int sectionIndex = this.heightContext.getSectionIndex(sectionY);
            if (sectionIndex < 0) {
                continue;
            }

            final ExtendedBlockStorage section = this.getChunkSection(chunk.x, sectionY, chunk.z);
            final boolean isEmpty = section == null || section.isEmpty();
            final Boolean queuedValue = effectiveChanges == null ? null : effectiveChanges[sectionIndex];
            final boolean changedFromKnown = knownEmptiness == null
                    || sectionIndex >= knownEmptiness.length
                    || knownEmptiness[sectionIndex] != isEmpty;

            if (queuedValue != null || changedFromKnown) {
                if (effectiveChanges == null) {
                    effectiveChanges = new Boolean[totalSections];
                }
                effectiveChanges[sectionIndex] = isEmpty;
            }
        }
        return effectiveChanges;
    }

    protected void processBlockPositionChanges(final Chunk chunk, final int chunkX, final int chunkZ, final IntOpenHashSet changedPositions) {
        final int minBlockY = this.heightContext.getMinBlockY();
        final int maxBlockY = this.heightContext.getMaxBlockY();
        final IntIterator it = changedPositions.iterator();
        while (it.hasNext()) {
            final int packed = it.nextInt();
            final int worldY = packed >> 8;
            if (worldY < minBlockY || worldY > maxBlockY) {
                continue;
            }
            final int worldX = (chunkX << 4) | (packed & 15);
            final int worldZ = (chunkZ << 4) | ((packed >> 4) & 15);
            this.lastPositionsProcessed++;
            this.propagateBlockChanges(chunk, worldX, worldY, worldZ);
        }
    }

    public final void light(final Chunk chunk, final Boolean[] emptySections, final boolean checkEdges) {
        final int chunkX = chunk.x;
        final int chunkZ = chunk.z;
        this.setupCaches(chunkX * 16 + 7, 128, chunkZ * 16 + 7, true, true);
        try {
            final SWMRNibbleArray[] nibbles = this.getFilledEmptyLight();
            this.setChunkInCache(chunkX, chunkZ, chunk);
            this.setBlocksForChunkInCache(chunkX, chunkZ, chunk.getBlockStorageArray());
            this.setNibblesForChunkInCache(chunkX, chunkZ, nibbles);
            this.setEmptinessMapCache(chunkX, chunkZ, this.getEmptinessMap(chunk));

            final boolean[] ret = this.handleEmptySectionChanges(chunk, emptySections, true);
            if (ret != null) {
                this.setEmptinessMap(chunk, ret);
            }
            this.lightChunk(chunk, checkEdges);
            this.updateVisible();
            this.setNibbles(chunk, this.getNibblesFromCache(chunk));
        } finally {
            this.destroyCaches();
        }
    }

    private SWMRNibbleArray[] getNibblesFromCache(final Chunk chunk) {
        final int chunkX = chunk.x;
        final int chunkZ = chunk.z;
        final SWMRNibbleArray[] nibbles = new SWMRNibbleArray[this.maxLightSection - this.minLightSection + 1];
        for (int cy = this.minLightSection; cy <= this.maxLightSection; ++cy) {
            nibbles[cy - this.minLightSection] = this.getNibbleFromCache(chunkX, cy, chunkZ);
        }
        return nibbles;
    }

    /**
     * Load-time init for a chunk restored with valid persisted light. Runs
     * only {@link #handleEmptySectionChanges} (nibble/emptiness-map setup) —
     * no BFS, no edge checks. Mirrors Starlight's
     * {@code StarLightEngine.forceHandleEmptySectionChanges}, which upstream
     * invokes for already-lit chunks instead of {@code light()}.
     */
    public final void loadInChunk(final Chunk chunk, final Boolean[] emptySections) {
        final int chunkX = chunk.x;
        final int chunkZ = chunk.z;
        this.setupCaches(chunkX * 16 + 7, 128, chunkZ * 16 + 7, true, true);
        try {
            final Chunk center = this.getChunkInCache(chunkX, chunkZ);
            if (center == null) {
                return;
            }
            final boolean[] ret = this.handleEmptySectionChanges(center, emptySections, false);
            if (ret != null) {
                this.setEmptinessMap(center, ret);
            }
            this.updateVisible();
        } finally {
            this.destroyCaches();
        }
    }

    public final void checkChunkEdges(final int chunkX, final int chunkZ, final IntOpenHashSet sections) {
        this.setupCaches(chunkX * 16 + 7, 128, chunkZ * 16 + 7, true, false);
        try {
            final Chunk chunk = this.getChunkInCache(chunkX, chunkZ);
            if (chunk == null) {
                return;
            }
            this.prepareBatchedEdgeChecks(chunkX, chunkZ);
            final IntIterator it = sections.iterator();
            while (it.hasNext()) {
                this.checkChunkEdge(chunkX, it.nextInt(), chunkZ);
                this.performLightDecrease();
            }
            this.updateVisible();
        } finally {
            this.destroyCaches();
        }
    }

    protected void prepareBatchedEdgeChecks(final int chunkX, final int chunkZ) {
    }

    /**
     * Process per-section emptiness changes.
     * {@code emptinessChanges} is a tri-state {@code Boolean[]}: {@code null}
     * means "no change" for that section index — sparse to avoid recomputing
     * unchanged sections.
     */
    protected final boolean[] handleEmptySectionChanges(final Chunk chunk, final Boolean[] emptinessChanges, final boolean unlit) {
        return this.sectionProcessor.handleEmptySectionChanges(chunk, emptinessChanges, unlit);
    }

    protected void checkChunkEdge(final int chunkX, final int chunkY, final int chunkZ) {
        this.sectionProcessor.checkChunkEdge(chunkX, chunkY, chunkZ);
    }

    protected boolean areBothEdgeSectionsFull(final int currentIndex, final int neighbourIndex) {
        return this.nibbleCache[currentIndex].isFullUpdating()
                && this.nibbleCache[neighbourIndex].isFullUpdating();
    }

    protected boolean areBothEdgeSectionsZero(final int currentIndex, final int neighbourIndex) {
        return this.nibbleCache[currentIndex].isZeroUpdating()
                && this.nibbleCache[neighbourIndex].isZeroUpdating();
    }

    protected void checkChunkEdges(final Chunk chunk, final int fromSection, final int toSection) {
        this.sectionProcessor.checkChunkEdges(chunk, fromSection, toSection);
    }

    protected void propagateNeighbourLevels(final Chunk chunk, final int fromSection, final int toSection) {
        this.sectionProcessor.propagateNeighbourLevels(chunk, fromSection, toSection);
    }

    protected static final long FLAG_WRITE_LEVEL = Long.MIN_VALUE >>> 2;
    protected static final long FLAG_RECHECK_LEVEL = Long.MIN_VALUE >>> 1;
    protected static final long FLAG_HAS_SIDED_TRANSPARENT_BLOCKS = Long.MIN_VALUE; // bit 63

    // 16 * 16 * 16 — matches Starlight (StarLightEngine.java:1023). The base
    // queues grow on demand via resize{Increase,Decrease}Queue up to MAX_QUEUE_SIZE.
    protected static final int INITIAL_QUEUE_SIZE = 1 << 12; // 4096
    protected static final int MAX_QUEUE_SIZE = 1 << 20; // ~8MB per queue

    protected boolean isMaxLight(final int level) {
        return level == 15;
    }

    protected long[] increaseQueue = new long[INITIAL_QUEUE_SIZE];
    protected int increaseQueueInitialLength;
    protected long[] decreaseQueue = new long[INITIAL_QUEUE_SIZE];
    protected int decreaseQueueInitialLength;
    protected boolean queueOverflowWarned;
    protected boolean queueOverflowed;

    protected final long[] resizeIncreaseQueue() {
        return this.increaseQueue = Arrays.copyOf(this.increaseQueue, Math.min(this.increaseQueue.length * 2, MAX_QUEUE_SIZE));
    }

    protected final long[] resizeDecreaseQueue() {
        return this.decreaseQueue = Arrays.copyOf(this.decreaseQueue, Math.min(this.decreaseQueue.length * 2, MAX_QUEUE_SIZE));
    }

    /**
     * Appends an entry to the increase queue. Returns {@code true} if the
     * entry was actually written; {@code false} if it was dropped due to
     * queue overflow. Callers that implement a "speculative append +
     * rollback" pattern (like {@code ScalarSkyEngine.tryPropagateSkylight})
     * MUST check the return value before decrementing
     * {@link #increaseQueueInitialLength} — an overflow drop does not
     * increment the length, so rolling back unconditionally would leave the
     * counter negative and crash the next BFS drain with an
     * {@code ArrayIndexOutOfBoundsException}.
     */
    protected final boolean appendToIncreaseQueue(final long value) {
        long[] queue = this.increaseQueue;
        final int idx = this.increaseQueueInitialLength;
        if (idx >= queue.length) {
            if (queue.length >= MAX_QUEUE_SIZE) {
                warnQueueOverflow();
                return false;
            }
            queue = this.resizeIncreaseQueue();
        }
        queue[idx] = value;
        this.increaseQueueInitialLength = idx + 1;
        return true;
    }

    /**
     * Appends an entry to the decrease queue. See
     * {@link #appendToIncreaseQueue(long)} for the return-value contract.
     */
    protected final boolean appendToDecreaseQueue(final long value) {
        long[] queue = this.decreaseQueue;
        final int idx = this.decreaseQueueInitialLength;
        if (idx >= queue.length) {
            if (queue.length >= MAX_QUEUE_SIZE) {
                warnQueueOverflow();
                return false;
            }
            queue = this.resizeDecreaseQueue();
        }
        queue[idx] = value;
        this.decreaseQueueInitialLength = idx + 1;
        return true;
    }

    private void warnQueueOverflow() {
        this.queueOverflowed = true;
        if (!this.queueOverflowWarned) {
            this.queueOverflowWarned = true;
            Pulsar.LOGGER.warn(
                    "Pulsar light queue overflow near chunk ({}, {}). Some blocks may remain dark. Chunk will be re-lit.",
                    2 - this.chunkOffsetX,
                    2 - this.chunkOffsetZ);
        }
    }

    public boolean wasQueueOverflowed() {
        return this.queueOverflowed;
    }

    protected static final AxisDirection[][] OLD_CHECK_DIRECTIONS = new AxisDirection[1 << 6][];
    protected static final int ALL_DIRECTIONS_BITSET = (1 << 6) - 1;

    static {
        for (int i = 0; i < OLD_CHECK_DIRECTIONS.length; ++i) {
            final List<AxisDirection> directions = new ArrayList<>();
            for (int bitset = i, len = Integer.bitCount(i), index = 0; index < len; ++index, bitset ^= (-bitset & bitset)) {
                directions.add(AXIS_DIRECTIONS[Integer.numberOfTrailingZeros(bitset)]);
            }
            OLD_CHECK_DIRECTIONS[i] = directions.toArray(new AxisDirection[0]);
        }
    }

    protected abstract void performLightIncrease();

    protected abstract void performLightDecrease();
}
