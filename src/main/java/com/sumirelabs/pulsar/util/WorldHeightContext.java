package com.sumirelabs.pulsar.util;

import java.util.Arrays;

/**
 * Immutable, per-world section bounds and logical-to-physical chunk-storage
 * mapping used by the lighting engine.
 *
 * <p>Pulsar keeps all of its own arrays in ascending world-section order.
 * Vanilla happens to store sections in that same order, but height-extension
 * mods are free to use a different physical layout. In particular, Depths
 * Update preserves vanilla indices {@code 0..15} and appends negative-Y
 * sections at the end in reverse order. Keeping that translation here stops
 * storage layout details from leaking into the BFS hot paths.
 */
public final class WorldHeightContext {

    public static final WorldHeightContext VANILLA = contiguous(0, 15);

    private final int minSection;
    private final int maxSection;
    private final int minLightSection;
    private final int maxLightSection;
    private final int[] storageIndexBySection;
    private final int[] sectionByStorageIndex;

    private WorldHeightContext(final int minSection, final int maxSection,
                               final int[] storageIndexBySection) {
        if (minSection > maxSection) {
            throw new IllegalArgumentException("Minimum section exceeds maximum section");
        }
        final int totalSections = maxSection - minSection + 1;
        if (storageIndexBySection.length != totalSections) {
            throw new IllegalArgumentException("Storage mapping length does not match section bounds");
        }

        this.minSection = minSection;
        this.maxSection = maxSection;
        this.minLightSection = minSection - 1;
        this.maxLightSection = maxSection + 1;
        this.storageIndexBySection = storageIndexBySection.clone();
        int maximumStorageIndex = -1;
        for (final int storageIndex : this.storageIndexBySection) {
            if (storageIndex < 0) {
                throw new IllegalArgumentException("Storage section index out of range: " + storageIndex);
            }
            maximumStorageIndex = Math.max(maximumStorageIndex, storageIndex);
        }
        this.sectionByStorageIndex = new int[maximumStorageIndex + 1];
        Arrays.fill(this.sectionByStorageIndex, Integer.MIN_VALUE);

        for (int logicalIndex = 0; logicalIndex < totalSections; ++logicalIndex) {
            final int storageIndex = this.storageIndexBySection[logicalIndex];
            if (this.sectionByStorageIndex[storageIndex] != Integer.MIN_VALUE) {
                throw new IllegalArgumentException("Duplicate storage section index: " + storageIndex);
            }
            this.sectionByStorageIndex[storageIndex] = logicalIndex + minSection;
        }
    }

    /**
     * Create a context whose physical array is ordered from {@code minSection}
     * through {@code maxSection}.
     */
    public static WorldHeightContext contiguous(final int minSection, final int maxSection) {
        if (minSection > maxSection) {
            throw new IllegalArgumentException("Minimum section exceeds maximum section");
        }
        final int[] mapping = new int[maxSection - minSection + 1];
        for (int i = 0; i < mapping.length; ++i) {
            mapping[i] = i;
        }
        return new WorldHeightContext(minSection, maxSection, mapping);
    }

    /**
     * Create a context with an explicit physical storage index for every
     * ascending logical section.
     */
    public static WorldHeightContext mapped(final int minSection, final int maxSection,
                                            final int[] storageIndexBySection) {
        return new WorldHeightContext(minSection, maxSection, storageIndexBySection);
    }

    public int getMinSection() {
        return this.minSection;
    }

    public int getMaxSection() {
        return this.maxSection;
    }

    public int getMinLightSection() {
        return this.minLightSection;
    }

    public int getMaxLightSection() {
        return this.maxLightSection;
    }

    public int getTotalSections() {
        return this.maxSection - this.minSection + 1;
    }

    public int getTotalLightSections() {
        return this.maxLightSection - this.minLightSection + 1;
    }

    public int getMinBlockY() {
        return this.minSection << 4;
    }

    public int getMaxBlockY() {
        return (this.maxSection << 4) | 15;
    }

    public boolean containsSection(final int sectionY) {
        return sectionY >= this.minSection && sectionY <= this.maxSection;
    }

    public boolean containsBlockY(final int blockY) {
        return blockY >= this.getMinBlockY() && blockY <= this.getMaxBlockY();
    }

    /**
     * Ascending logical-array index for a world section, or {@code -1} when
     * the section is outside the block bounds.
     */
    public int getSectionIndex(final int sectionY) {
        return this.containsSection(sectionY) ? sectionY - this.minSection : -1;
    }

    /**
     * Ascending Pulsar light-array index for a world section, including the
     * one-section boundary on either side, or {@code -1} when out of range.
     */
    public int getLightSectionIndex(final int sectionY) {
        return sectionY >= this.minLightSection && sectionY <= this.maxLightSection
                ? sectionY - this.minLightSection : -1;
    }

    /**
     * Physical {@code Chunk.storageArrays} index for a world section, or
     * {@code -1} when the section is outside the block bounds.
     */
    public int getStorageIndex(final int sectionY) {
        final int logicalIndex = this.getSectionIndex(sectionY);
        return logicalIndex < 0 ? -1 : this.storageIndexBySection[logicalIndex];
    }

    /**
     * World section represented by a physical chunk-storage index, or
     * {@link Integer#MIN_VALUE} when the index is outside this context.
     */
    public int getSectionForStorageIndex(final int storageIndex) {
        return storageIndex >= 0 && storageIndex < this.sectionByStorageIndex.length
                ? this.sectionByStorageIndex[storageIndex] : Integer.MIN_VALUE;
    }
}
