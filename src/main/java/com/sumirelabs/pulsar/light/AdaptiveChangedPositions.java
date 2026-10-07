package com.sumirelabs.pulsar.light;

import it.unimi.dsi.fastutil.ints.AbstractIntIterator;
import it.unimi.dsi.fastutil.ints.AbstractIntSet;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.util.BitSet;
import java.util.NoSuchElementException;

/** Dense section-local bitmap storage for a promoted block-change batch. */
final class AdaptiveChangedPositions extends AbstractIntSet {

    /** Minimum number of unique edits before a promotion is considered. */
    static final int DENSE_THRESHOLD = 4096;
    /** Approximate occupancy where a 512-byte section bitmap beats hash storage. */
    static final int DENSE_SECTION_OCCUPANCY = 96;
    private static final int POSITIONS_PER_SECTION = 16 * 256;

    private final Int2ObjectOpenHashMap<BitSet> dense = new Int2ObjectOpenHashMap<>();
    private int size;

    private AdaptiveChangedPositions(final IntSet initialPositions) {
        this.size = initialPositions.size();
        final IntIterator iterator = initialPositions.iterator();
        while (iterator.hasNext()) {
            this.set(iterator.nextInt());
        }
    }

    /**
     * Evaluates a sparse batch at its scheduled promotion point.
     *
     * @return a dense representation when the conservative section-span gate
     * is met, and the next size at which to check otherwise
     */
    static PromotionResult considerPromotion(final IntOpenHashSet sparse) {
        int minSection = Integer.MAX_VALUE;
        int maxSection = Integer.MIN_VALUE;
        final IntIterator iterator = sparse.iterator();
        while (iterator.hasNext()) {
            final int sectionY = (iterator.nextInt() >> 8) >> 4;
            if (sectionY < minSection) {
                minSection = sectionY;
            }
            if (sectionY > maxSection) {
                maxSection = sectionY;
            }
        }
        final long sectionSpan = (long) maxSection - minSection + 1L;
        final long required = Math.max(DENSE_THRESHOLD, sectionSpan * DENSE_SECTION_OCCUPANCY);
        if (sparse.size() >= required) {
            return new PromotionResult(new AdaptiveChangedPositions(sparse), DENSE_THRESHOLD);
        }
        final long nextCheck = Math.max((long) sparse.size() + 512L, required);
        return new PromotionResult(null,
                nextCheck >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) nextCheck);
    }

    @Override
    public boolean add(final int packedPosition) {
        final int worldY = packedPosition >> 8;
        final int sectionY = worldY >> 4;
        BitSet positions = this.dense.get(sectionY);
        if (positions == null) {
            positions = new BitSet(POSITIONS_PER_SECTION);
            this.dense.put(sectionY, positions);
        }
        final int sectionPosition = ((worldY & 15) << 8) | (packedPosition & 255);
        if (positions.get(sectionPosition)) {
            return false;
        }
        positions.set(sectionPosition);
        ++this.size;
        return true;
    }

    @Override
    public boolean contains(final int packedPosition) {
        final int worldY = packedPosition >> 8;
        final BitSet positions = this.dense.get(worldY >> 4);
        return positions != null
                && positions.get(((worldY & 15) << 8) | (packedPosition & 255));
    }

    @Override
    public boolean rem(final int packedPosition) {
        final int worldY = packedPosition >> 8;
        final int sectionY = worldY >> 4;
        final BitSet positions = this.dense.get(sectionY);
        if (positions == null) {
            return false;
        }
        final int sectionPosition = ((worldY & 15) << 8) | (packedPosition & 255);
        if (!positions.get(sectionPosition)) {
            return false;
        }
        positions.clear(sectionPosition);
        if (positions.isEmpty()) {
            this.dense.remove(sectionY);
        }
        --this.size;
        return true;
    }

    @Override
    public int size() {
        return this.size;
    }

    @Override
    public void clear() {
        this.dense.clear();
        this.size = 0;
    }

    @Override
    public IntIterator iterator() {
        return new DenseIterator(this.dense);
    }

    private void set(final int packedPosition) {
        final int worldY = packedPosition >> 8;
        final int sectionY = worldY >> 4;
        BitSet positions = this.dense.get(sectionY);
        if (positions == null) {
            positions = new BitSet(POSITIONS_PER_SECTION);
            this.dense.put(sectionY, positions);
        }
        positions.set(((worldY & 15) << 8) | (packedPosition & 255));
    }

    static final class PromotionResult {
        final AdaptiveChangedPositions promoted;
        final int nextCheckSize;

        PromotionResult(final AdaptiveChangedPositions promoted, final int nextCheckSize) {
            this.promoted = promoted;
            this.nextCheckSize = nextCheckSize;
        }
    }

    private static final class DenseIterator extends AbstractIntIterator {
        private final ObjectIterator<Int2ObjectMap.Entry<BitSet>> sections;
        private Int2ObjectMap.Entry<BitSet> section;
        private int nextPosition;
        private int nextIndex = -1;

        private DenseIterator(final Int2ObjectOpenHashMap<BitSet> dense) {
            this.sections = dense.int2ObjectEntrySet().iterator();
        }

        @Override
        public boolean hasNext() {
            this.advance();
            return this.nextIndex >= 0;
        }

        @Override
        public int nextInt() {
            this.advance();
            if (this.nextIndex < 0) {
                throw new NoSuchElementException();
            }
            final int sectionY = this.section.getIntKey();
            final int position = this.nextIndex;
            this.nextPosition = position + 1;
            this.nextIndex = -1;
            final int worldY = (sectionY << 4) | (position >>> 8);
            return (worldY << 8) | (position & 255);
        }

        private void advance() {
            if (this.nextIndex >= 0) {
                return;
            }
            while (true) {
                if (this.section != null) {
                    this.nextIndex = this.section.getValue().nextSetBit(this.nextPosition);
                    if (this.nextIndex >= 0) {
                        return;
                    }
                }
                if (!this.sections.hasNext()) {
                    return;
                }
                this.section = this.sections.next();
                this.nextPosition = 0;
            }
        }
    }
}
