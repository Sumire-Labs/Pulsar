package com.sumirelabs.pulsar.light.engine;

import com.sumirelabs.pulsar.light.LightStats;
import com.sumirelabs.pulsar.light.SWMRNibbleArray;
import net.minecraft.block.state.IBlockState;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/**
 * Maintains section-nibble topology and reconciles horizontal chunk edges for
 * one {@link PulsarEngine}. It uses the engine's task-local cache and queues;
 * instances therefore share the same lifecycle as their owning engine.
 */
final class LightSectionProcessor {

    private final PulsarEngine engine;
    private final int[] centerDelayedUpdates = new int[16 * 16];
    private final int[] neighbourDelayedUpdates = new int[16 * 16];

    LightSectionProcessor(final PulsarEngine engine) {
        this.engine = engine;
    }

    /**
     * Applies sparse section-emptiness changes and initializes nearby nibbles.
     */
    boolean[] handleEmptySectionChanges(final Chunk chunk,
                                        final Boolean[] emptinessChanges,
                                        final boolean unlit) {
        final PulsarEngine engine = this.engine;
        final int chunkX = chunk.x;
        final int chunkZ = chunk.z;

        boolean[] chunkEmptinessMap = engine.getEmptinessMap(chunkX, chunkZ);
        boolean[] initializedMap = null;
        final boolean needsInit = unlit || chunkEmptinessMap == null;

        if (needsInit) {
            initializedMap = chunkEmptinessMap =
                    new boolean[engine.heightContext.getTotalSections()];
            engine.setEmptinessMapCache(chunkX, chunkZ, chunkEmptinessMap);
        }

        for (int sectionIndex = emptinessChanges.length - 1; sectionIndex >= 0; --sectionIndex) {
            Boolean empty = emptinessChanges[sectionIndex];
            if (empty == null) {
                if (!needsInit) {
                    continue;
                }
                final ExtendedBlockStorage section = engine.getChunkSection(
                        chunkX, sectionIndex + engine.minSection, chunkZ);
                empty = section == null || section.isEmpty() ? Boolean.TRUE : Boolean.FALSE;
                emptinessChanges[sectionIndex] = empty;
            }
            chunkEmptinessMap[sectionIndex] = empty;
        }

        // A newly non-empty section requires its 3x3x3 nibble neighbourhood.
        for (int sectionIndex = emptinessChanges.length - 1; sectionIndex >= 0; --sectionIndex) {
            final Boolean empty = emptinessChanges[sectionIndex];
            final int sectionY = sectionIndex + engine.minSection;
            if (empty == null || empty) {
                continue;
            }
            for (int deltaZ = -1; deltaZ <= 1; ++deltaZ) {
                for (int deltaX = -1; deltaX <= 1; ++deltaX) {
                    final boolean extrude = (deltaX | deltaZ) != 0 || !unlit;
                    for (int deltaY = 1; deltaY >= -1; --deltaY) {
                        engine.initNibble(
                                chunkX + deltaX, sectionY + deltaY, chunkZ + deltaZ,
                                extrude, false);
                    }
                }
            }
        }

        this.refreshLazyNibbles(chunkX, chunkZ, unlit);
        return initializedMap;
    }

    private void refreshLazyNibbles(final int chunkX, final int chunkZ, final boolean unlit) {
        final PulsarEngine engine = this.engine;
        for (int deltaZ = -1; deltaZ <= 1; ++deltaZ) {
            for (int deltaX = -1; deltaX <= 1; ++deltaX) {
                boolean neighboursLoaded = true;
                neighbourLoadedSearch:
                for (int neighbourZ = -1; neighbourZ <= 1; ++neighbourZ) {
                    for (int neighbourX = -1; neighbourX <= 1; ++neighbourX) {
                        if (engine.getEmptinessMap(
                                chunkX + deltaX + neighbourX,
                                chunkZ + deltaZ + neighbourZ) == null) {
                            neighboursLoaded = false;
                            break neighbourLoadedSearch;
                        }
                    }
                }

                for (int sectionY = engine.maxLightSection;
                     sectionY >= engine.minLightSection; --sectionY) {
                    final boolean allEmpty = this.isNeighbourhoodEmpty(
                            chunkX + deltaX, sectionY, chunkZ + deltaZ);
                    if (allEmpty & neighboursLoaded) {
                        engine.setNibbleNull(chunkX + deltaX, sectionY, chunkZ + deltaZ);
                    } else if (!allEmpty) {
                        final boolean extrude = (deltaX | deltaZ) != 0 || !unlit;
                        engine.initNibble(
                                chunkX + deltaX, sectionY, chunkZ + deltaZ,
                                extrude, false);
                    }
                }
            }
        }
    }

    private boolean isNeighbourhoodEmpty(final int chunkX, final int sectionY, final int chunkZ) {
        final PulsarEngine engine = this.engine;
        for (int deltaY = -1; deltaY <= 1; ++deltaY) {
            for (int deltaZ = -1; deltaZ <= 1; ++deltaZ) {
                for (int deltaX = -1; deltaX <= 1; ++deltaX) {
                    final int neighbourY = sectionY + deltaY;
                    if (neighbourY < engine.minSection || neighbourY > engine.maxSection) {
                        continue;
                    }
                    final boolean[] emptinessMap =
                            engine.getEmptinessMap(chunkX + deltaX, chunkZ + deltaZ);
                    if (emptinessMap != null) {
                        if (!emptinessMap[neighbourY - engine.minSection]) {
                            return false;
                        }
                    } else {
                        final ExtendedBlockStorage section = engine.getChunkSection(
                                chunkX + deltaX, neighbourY, chunkZ + deltaZ);
                        if (section != null && !section.isEmpty()) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    void checkChunkEdges(final Chunk chunk, final int fromSection, final int toSection) {
        for (int sectionY = toSection; sectionY >= fromSection; --sectionY) {
            this.engine.checkChunkEdge(chunk.x, sectionY, chunk.z);
        }
        this.engine.performLightDecrease();
    }

    void checkChunkEdge(final int chunkX, final int chunkY, final int chunkZ) {
        final PulsarEngine engine = this.engine;
        final int currentCacheIndex = chunkX + 5 * chunkZ + (5 * 5) * chunkY
                + engine.chunkSectionIndexOffset;
        final SWMRNibbleArray currentNibble = engine.nibbleCache[currentCacheIndex];
        if (currentNibble == null) {
            return;
        }

        final LightStats stats = engine.stats;
        for (final PulsarEngine.AxisDirection direction : PulsarEngine.ONLY_HORIZONTAL_DIRECTIONS) {
            final int neighbourOffsetX = direction.x;
            final int neighbourOffsetZ = direction.z;
            final int neighbourCacheIndex = (chunkX + neighbourOffsetX)
                    + 5 * (chunkZ + neighbourOffsetZ) + (5 * 5) * chunkY
                    + engine.chunkSectionIndexOffset;
            final SWMRNibbleArray neighbourNibble = engine.nibbleCache[neighbourCacheIndex];
            if (neighbourNibble == null) {
                continue;
            }
            if (!currentNibble.isInitialisedUpdating() && !neighbourNibble.isInitialisedUpdating()) {
                continue;
            }
            if (engine.areBothEdgeSectionsFull(currentCacheIndex, neighbourCacheIndex)) {
                if (stats != null) stats.edgeSectionPairsSkippedFull.incrementAndGet();
                continue;
            }
            if (engine.areBothEdgeSectionsZero(currentCacheIndex, neighbourCacheIndex)) {
                if (stats != null) stats.edgeSectionPairsSkippedZero.incrementAndGet();
                continue;
            }
            if (stats != null) stats.edgeSectionPairsChecked.incrementAndGet();

            this.reconcileEdge(
                    chunkX, chunkY, chunkZ, direction,
                    currentCacheIndex, neighbourCacheIndex, stats);
        }
    }

    private void reconcileEdge(final int chunkX, final int chunkY, final int chunkZ,
                               final PulsarEngine.AxisDirection direction,
                               final int currentCacheIndex, final int neighbourCacheIndex,
                               final LightStats stats) {
        final PulsarEngine engine = this.engine;
        final int neighbourOffsetX = direction.x;
        final int neighbourOffsetZ = direction.z;
        final int incrementX;
        final int incrementZ;
        final int startX;
        final int startZ;
        if (neighbourOffsetX != 0) {
            incrementX = 0;
            incrementZ = 1;
            startX = direction.x < 0 ? chunkX << 4 : chunkX << 4 | 15;
            startZ = chunkZ << 4;
        } else {
            incrementX = 1;
            incrementZ = 0;
            startZ = neighbourOffsetZ < 0 ? chunkZ << 4 : chunkZ << 4 | 15;
            startX = chunkX << 4;
        }

        int centerDelayedCount = 0;
        int neighbourDelayedCount = 0;
        int blocksTrivial = 0;
        int blocksRecalculated = 0;
        int blocksMismatched = 0;
        for (int worldY = chunkY << 4, maxY = worldY | 15; worldY <= maxY; ++worldY) {
            for (int index = 0, worldX = startX, worldZ = startZ;
                 index < 16;
                 ++index, worldX += incrementX, worldZ += incrementZ) {
                final int neighbourX = worldX + neighbourOffsetX;
                final int neighbourZ = worldZ + neighbourOffsetZ;
                final int currentIndex =
                        (worldX & 15) | ((worldZ & 15) << 4) | ((worldY & 15) << 8);
                final int currentLevel = engine.getLightLevel(currentCacheIndex, currentIndex);
                final int neighbourIndex =
                        (neighbourX & 15) | ((neighbourZ & 15) << 4) | ((worldY & 15) << 8);
                final int neighbourLevel = engine.getLightLevel(neighbourCacheIndex, neighbourIndex);

                if (currentLevel == 0 && neighbourLevel == 0) {
                    ++blocksTrivial;
                    continue;
                }

                blocksRecalculated += 2;
                if (engine.calculateLightValue(worldX, worldY, worldZ, currentLevel) != currentLevel) {
                    this.centerDelayedUpdates[centerDelayedCount++] = currentIndex;
                    ++blocksMismatched;
                }
                if (engine.calculateLightValue(
                        neighbourX, worldY, neighbourZ, neighbourLevel) != neighbourLevel) {
                    this.neighbourDelayedUpdates[neighbourDelayedCount++] = neighbourIndex;
                    ++blocksMismatched;
                }
            }
        }

        if (stats != null) {
            stats.edgeBlocksTotal.addAndGet(256);
            stats.edgeBlocksSkippedTrivial.addAndGet(blocksTrivial);
            // The old consistency shortcut is intentionally disabled because
            // it is invalid for seam opacity greater than one.
            stats.edgeBlocksSkippedConsistency.addAndGet(0);
            stats.edgeBlocksRecalculated.addAndGet(blocksRecalculated);
            stats.edgeBlocksMismatched.addAndGet(blocksMismatched);
        }

        this.enqueueMismatches(
                chunkX, chunkY, chunkZ, direction, centerDelayedCount, neighbourDelayedCount);
    }

    private void enqueueMismatches(final int chunkX, final int chunkY, final int chunkZ,
                                   final PulsarEngine.AxisDirection direction,
                                   final int centerCount, final int neighbourCount) {
        final int currentChunkOffsetX = chunkX << 4;
        final int currentChunkOffsetZ = chunkZ << 4;
        final int neighbourChunkOffsetX = (chunkX + direction.x) << 4;
        final int neighbourChunkOffsetZ = (chunkZ + direction.z) << 4;
        final int chunkOffsetY = chunkY << 4;
        final int maxCount = Math.max(centerCount, neighbourCount);
        for (int index = 0; index < maxCount; ++index) {
            if (index < centerCount) {
                final int value = this.centerDelayedUpdates[index];
                this.engine.checkBlock(
                        currentChunkOffsetX | (value & 15),
                        chunkOffsetY | (value >>> 8),
                        currentChunkOffsetZ | ((value >>> 4) & 15));
            }
            if (index < neighbourCount) {
                final int value = this.neighbourDelayedUpdates[index];
                this.engine.checkBlock(
                        neighbourChunkOffsetX | (value & 15),
                        chunkOffsetY | (value >>> 8),
                        neighbourChunkOffsetZ | ((value >>> 4) & 15));
            }
        }
    }

    void propagateNeighbourLevels(final Chunk chunk, final int fromSection, final int toSection) {
        final PulsarEngine engine = this.engine;
        final int chunkX = chunk.x;
        final int chunkZ = chunk.z;
        final int directionShift = engine.getDirectionShift();

        for (int sectionY = toSection; sectionY >= fromSection; --sectionY) {
            final SWMRNibbleArray currentNibble = engine.getNibbleFromCache(chunkX, sectionY, chunkZ);
            if (currentNibble == null) {
                continue;
            }
            for (final PulsarEngine.AxisDirection direction : PulsarEngine.ONLY_HORIZONTAL_DIRECTIONS) {
                final int neighbourOffsetX = direction.x;
                final int neighbourOffsetZ = direction.z;
                final int neighbourCacheIndex = (chunkX + neighbourOffsetX)
                        + 5 * (chunkZ + neighbourOffsetZ) + (5 * 5) * sectionY
                        + engine.chunkSectionIndexOffset;
                if (engine.nibbleCache[neighbourCacheIndex] == null
                        || !engine.nibbleCache[neighbourCacheIndex].isInitialisedUpdating()) {
                    continue;
                }

                final int incrementX;
                final int incrementZ;
                final int startX;
                final int startZ;
                if (neighbourOffsetX != 0) {
                    incrementX = 0;
                    incrementZ = 1;
                    startX = direction.x < 0 ? (chunkX << 4) - 1 : (chunkX << 4) + 16;
                    startZ = chunkZ << 4;
                } else {
                    incrementX = 1;
                    incrementZ = 0;
                    startZ = neighbourOffsetZ < 0 ? (chunkZ << 4) - 1 : (chunkZ << 4) + 16;
                    startX = chunkX << 4;
                }

                final long propagateDirection = 1L << direction.oppositeOrdinal;
                final int encodeOffset = engine.coordinateOffset;
                for (int worldY = sectionY << 4, maxY = worldY | 15;
                     worldY <= maxY; ++worldY) {
                    for (int index = 0, worldX = startX, worldZ = startZ;
                         index < 16;
                         ++index, worldX += incrementX, worldZ += incrementZ) {
                        final int localIndex =
                                (worldX & 15) | ((worldZ & 15) << 4) | ((worldY & 15) << 8);
                        final int level = engine.getLightLevel(neighbourCacheIndex, localIndex);
                        if (engine.isBelowPropagationThreshold(level)) {
                            continue;
                        }
                        final int edgeCacheIndex = (worldX >> 4) + 5 * (worldZ >> 4)
                                + (5 * 5) * (worldY >> 4) + engine.chunkSectionIndexOffset;
                        final IBlockState edgeState = engine.getBlockStateFast(
                                edgeCacheIndex, worldX & 15, worldY & 15, worldZ & 15);
                        engine.appendToIncreaseQueue(
                                PulsarEngine.encodeCoords(worldX, worldZ, worldY, encodeOffset)
                                        | engine.encodeQueueLevel(level)
                                        | (propagateDirection << directionShift)
                                        | PulsarEngine.sidedFlag(edgeState));
                    }
                }
            }
        }
    }
}
