package com.sumirelabs.pulsar.light.engine;

import com.sumirelabs.pulsar.light.SWMRNibbleArray;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.init.Blocks;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.Arrays;

/**
 * Handles skylight-specific vertical extrusion and batched changed-column
 * seeding. Horizontal BFS remains in {@link ScalarSkyEngine}.
 */
final class SkyLightColumnProcessor {

    private final ScalarSkyEngine engine;
    private final boolean[] nullPropagationChecks;

    SkyLightColumnProcessor(final ScalarSkyEngine engine) {
        this.engine = engine;
        this.nullPropagationChecks = new boolean[engine.heightContext.getTotalLightSections()];
    }

    void initNibble(final int chunkX, final int chunkY, final int chunkZ,
                    final boolean extrude, final boolean initRemovedNibbles) {
        final ScalarSkyEngine engine = this.engine;
        if (chunkY < engine.minLightSection || chunkY > engine.maxLightSection
                || engine.getChunkInCache(chunkX, chunkZ) == null) {
            return;
        }
        SWMRNibbleArray nibble = engine.getNibbleFromCache(chunkX, chunkY, chunkZ);
        if (nibble == null) {
            if (!initRemovedNibbles) {
                return;
            }
            nibble = new SWMRNibbleArray(null, true);
            engine.setNibbleInCache(chunkX, chunkY, chunkZ, nibble);
        }
        this.initSkyNibble(nibble, chunkX, chunkY, chunkZ, extrude);
    }

    private void initSkyNibble(final SWMRNibbleArray nibble,
                               final int chunkX, final int chunkY, final int chunkZ,
                               final boolean extrude) {
        final ScalarSkyEngine engine = this.engine;
        if (!nibble.isNullNibbleUpdating()) {
            return;
        }
        if (chunkY > engine.maxSection) {
            nibble.setFull();
            return;
        }
        if (chunkY < engine.minSection) {
            nibble.setNonNull();
            nibble.setZero();
            return;
        }

        final boolean[] emptinessMap = engine.getEmptinessMap(chunkX, chunkZ);
        int lowestNonEmpty = engine.minSection - 1;
        for (int sectionY = engine.maxSection; sectionY >= engine.minSection; --sectionY) {
            if (emptinessMap != null) {
                if (emptinessMap[sectionY - engine.minSection]) {
                    continue;
                }
            } else {
                final ExtendedBlockStorage section =
                        engine.getChunkSection(chunkX, sectionY, chunkZ);
                if (section == null || section.isEmpty()) {
                    continue;
                }
            }
            lowestNonEmpty = sectionY;
            break;
        }

        if (chunkY > lowestNonEmpty) {
            nibble.setNonNull();
            nibble.setFull();
        } else if (extrude) {
            for (int currentY = chunkY + 1;
                 currentY <= engine.maxLightSection; ++currentY) {
                final SWMRNibbleArray above =
                        engine.getNibbleFromCache(chunkX, currentY, chunkZ);
                if (above != null && !above.isNullNibbleUpdating()) {
                    nibble.setNonNull();
                    nibble.extrudeLower(above);
                    break;
                }
            }
        } else {
            nibble.setNonNull();
        }
    }

    void setNibbleNull(final int chunkX, final int chunkY, final int chunkZ) {
        final SWMRNibbleArray nibble = this.engine.getNibbleFromCache(chunkX, chunkY, chunkZ);
        if (nibble != null) {
            nibble.setNull();
        }
    }

    void rewriteNibbleCache() {
        final SWMRNibbleArray[] cache = this.engine.nibbleCache;
        for (int index = 0; index < cache.length; ++index) {
            final SWMRNibbleArray nibble = cache[index];
            if (nibble != null && nibble.isNullNibbleUpdating()) {
                cache[index] = null;
                nibble.updateVisible();
            }
        }
    }

    void resetNullPropagationChecks() {
        Arrays.fill(this.nullPropagationChecks, false);
    }

    void prepareBatchedEdgeChecks(final int chunkX, final int chunkZ) {
        final ScalarSkyEngine engine = this.engine;
        this.resetNullPropagationChecks();
        this.rewriteNibbleCache();
        for (int sectionY = engine.maxLightSection;
             sectionY >= engine.minLightSection; --sectionY) {
            this.checkNullSection(chunkX, sectionY, chunkZ, true);
        }
    }

    boolean checkNullSection(final int chunkX, final int chunkY, final int chunkZ,
                             final boolean extrudeInitialised) {
        final ScalarSkyEngine engine = this.engine;
        if (chunkY < engine.minLightSection || chunkY > engine.maxLightSection
                || this.nullPropagationChecks[chunkY - engine.minLightSection]) {
            return false;
        }
        this.nullPropagationChecks[chunkY - engine.minLightSection] = true;

        boolean needInitNeighbours = false;
        neighbourSearch:
        for (int deltaZ = -1; deltaZ <= 1; ++deltaZ) {
            for (int deltaX = -1; deltaX <= 1; ++deltaX) {
                final SWMRNibbleArray nibble =
                        engine.getNibbleFromCache(chunkX + deltaX, chunkY, chunkZ + deltaZ);
                if (nibble != null && !nibble.isNullNibbleUpdating()) {
                    needInitNeighbours = true;
                    break neighbourSearch;
                }
            }
        }

        if (needInitNeighbours) {
            for (int deltaZ = -1; deltaZ <= 1; ++deltaZ) {
                for (int deltaX = -1; deltaX <= 1; ++deltaX) {
                    this.initNibble(
                            chunkX + deltaX, chunkY, chunkZ + deltaZ,
                            (deltaX | deltaZ) != 0 || extrudeInitialised, true);
                }
            }
        }
        return needInitNeighbours;
    }

    private int getLightLevelExtruded(final int worldX, final int worldY, final int worldZ) {
        final ScalarSkyEngine engine = this.engine;
        final int chunkX = worldX >> 4;
        int chunkY = worldY >> 4;
        final int chunkZ = worldZ >> 4;

        final int cacheIndex = chunkX + 5 * chunkZ + (5 * 5) * chunkY
                + engine.chunkSectionIndexOffset;
        if (engine.nibbleCache[cacheIndex] != null) {
            return engine.getLightLevel(
                    cacheIndex,
                    (worldX & 15) | ((worldZ & 15) << 4) | ((worldY & 15) << 8));
        }

        while (++chunkY <= engine.maxLightSection) {
            final int nextCacheIndex = chunkX + 5 * chunkZ + (5 * 5) * chunkY
                    + engine.chunkSectionIndexOffset;
            if (engine.nibbleCache[nextCacheIndex] != null) {
                return engine.getLightLevel(
                        nextCacheIndex, (worldX & 15) | ((worldZ & 15) << 4));
            }
        }
        return 15;
    }

    int tryPropagateSkylight(final int worldX, int startY, final int worldZ,
                             final boolean extrudeInitialised, final boolean delayLightSet) {
        final ScalarSkyEngine engine = this.engine;
        final int encodeOffset = engine.coordinateOffset;
        final long propagateDirection =
                PulsarEngine.AxisDirection.POSITIVE_Y.everythingButThisDirection;

        final int extrudedLevel = this.getLightLevelExtruded(worldX, startY + 1, worldZ);
        if (extrudedLevel == 0) {
            return startY;
        }

        this.checkNullSection(worldX >> 4, startY >> 4, worldZ >> 4, extrudeInitialised);
        int currentSky = extrudedLevel;
        int aboveInfo = engine.lightInfoAt(
                engine.getBlockState(worldX, startY + 1, worldZ),
                worldX, startY + 1, worldZ);

        for (; startY >= (engine.minLightSection << 4); --startY) {
            if ((startY & 15) == 15) {
                this.checkNullSection(worldX >> 4, startY >> 4, worldZ >> 4, extrudeInitialised);
            }
            final int currentInfo = engine.lightInfoAt(
                    engine.getBlockState(worldX, startY, worldZ), worldX, startY, worldZ);

            final int aboveOpacity = LightInfo.opacity(aboveInfo);
            if (aboveOpacity > 0
                    && ((aboveInfo & LightInfo.REGISTRY) == 0
                    || LightInfo.isFaceSolid(aboveInfo, 5))) {
                break;
            }

            final int currentOpacity = LightInfo.opacity(currentInfo);
            if (currentOpacity > 0
                    && ((currentInfo & LightInfo.REGISTRY) == 0
                    || LightInfo.isFaceSolid(currentInfo, 4))) {
                break;
            }
            if (currentOpacity > 0) {
                currentSky -= currentOpacity;
                if (currentSky <= 0) {
                    break;
                }
            }

            final long speculativeValue = PulsarEngine.encodeCoords(
                    worldX, worldZ, startY, encodeOffset)
                    | engine.encodeQueueLevel(currentSky)
                    | (propagateDirection << PulsarEngine.DIRECTION_SHIFT)
                    | PulsarEngine.sidedFlag(currentInfo);
            final boolean appended = engine.appendToIncreaseQueue(speculativeValue);

            if (engine.getNibbleFromCache(worldX >> 4, startY >> 4, worldZ >> 4) == null) {
                if (appended) {
                    --engine.increaseQueueInitialLength;
                }
                startY &= ~15;
                aboveInfo = LightInfo.of(Blocks.AIR.getDefaultState());
            } else {
                if (!delayLightSet) {
                    engine.setLightLevel(worldX, startY, worldZ, currentSky);
                }
                aboveInfo = currentInfo;
            }
        }
        return startY;
    }

    void processBlockPositionChanges(final int chunkX, final int chunkZ,
                                     final IntOpenHashSet changedPositions) {
        final ScalarSkyEngine engine = this.engine;
        this.rewriteNibbleCache();
        Arrays.fill(this.nullPropagationChecks, false);

        final int minBlockY = engine.heightContext.getMinBlockY();
        final int maxBlockY = engine.heightContext.getMaxBlockY();
        final int[] columnMaxY = new int[256];
        Arrays.fill(columnMaxY, Integer.MIN_VALUE);

        IntIterator iterator = changedPositions.iterator();
        while (iterator.hasNext()) {
            final int packed = iterator.nextInt();
            final int worldY = packed >> 8;
            if (worldY < minBlockY || worldY > maxBlockY) {
                continue;
            }
            final int column = packed & 255;
            if (worldY > columnMaxY[column]) {
                columnMaxY[column] = worldY;
            }
        }

        final long propagateDirection =
                PulsarEngine.AxisDirection.POSITIVE_Y.everythingButThisDirection;
        final int encodeOffset = engine.coordinateOffset;
        for (int column = 0; column < 256; ++column) {
            final int maximumY = columnMaxY[column];
            if (maximumY == Integer.MIN_VALUE) {
                continue;
            }
            final int worldX = (chunkX << 4) | (column & 15);
            final int worldZ = (chunkZ << 4) | (column >> 4);
            final int maximumPropagationY =
                    this.tryPropagateSkylight(worldX, maximumY, worldZ, true, true);
            this.seedFullColumnDecrease(
                    worldX, maximumPropagationY, worldZ, propagateDirection, encodeOffset);
        }

        this.applyDelayedQueue(engine.increaseQueue, engine.increaseQueueInitialLength, true);
        this.applyDelayedQueue(engine.decreaseQueue, engine.decreaseQueueInitialLength, false);

        iterator = changedPositions.iterator();
        while (iterator.hasNext()) {
            final int packed = iterator.nextInt();
            final int worldY = packed >> 8;
            if (worldY < minBlockY || worldY > maxBlockY) {
                continue;
            }
            ++engine.lastPositionsProcessed;
            engine.checkBlock(
                    (chunkX << 4) | (packed & 15),
                    worldY,
                    (chunkZ << 4) | ((packed >> 4) & 15));
        }
        engine.performLightDecrease();
    }

    void propagateBlockChange(final int blockX, final int blockY, final int blockZ) {
        final ScalarSkyEngine engine = this.engine;
        this.rewriteNibbleCache();
        Arrays.fill(this.nullPropagationChecks, false);

        final int maximumPropagationY =
                this.tryPropagateSkylight(blockX, blockY, blockZ, true, true);
        final long propagateDirection =
                PulsarEngine.AxisDirection.POSITIVE_Y.everythingButThisDirection;
        this.seedFullColumnDecrease(
                blockX, maximumPropagationY, blockZ,
                propagateDirection, engine.coordinateOffset);

        this.applyDelayedQueue(engine.increaseQueue, engine.increaseQueueInitialLength, true);
        this.applyDelayedQueue(engine.decreaseQueue, engine.decreaseQueueInitialLength, false);
        engine.checkBlock(blockX, blockY, blockZ);
        engine.performLightDecrease();
    }

    private void seedFullColumnDecrease(final int worldX, final int maximumPropagationY,
                                        final int worldZ, final long propagateDirection,
                                        final int encodeOffset) {
        final ScalarSkyEngine engine = this.engine;
        if (this.getLightLevelExtruded(worldX, maximumPropagationY, worldZ) != 15) {
            return;
        }
        this.checkNullSection(
                worldX >> 4, maximumPropagationY >> 4, worldZ >> 4, true);
        for (int currentY = maximumPropagationY;
             currentY >= (engine.minLightSection << 4); --currentY) {
            if ((currentY & 15) == 15) {
                this.checkNullSection(worldX >> 4, currentY >> 4, worldZ >> 4, true);
            }
            final SWMRNibbleArray nibble = engine.nibbleCache[
                    (worldX >> 4) + 5 * (worldZ >> 4) + (5 * 5) * (currentY >> 4)
                            + engine.chunkSectionIndexOffset];
            if (nibble == null) {
                currentY &= ~15;
                continue;
            }
            if (engine.getLightLevel(worldX, currentY, worldZ) != 15) {
                break;
            }
            engine.appendToDecreaseQueue(
                    PulsarEngine.encodeCoords(worldX, worldZ, currentY, encodeOffset)
                            | engine.encodeQueueLevel(15)
                            | (propagateDirection << PulsarEngine.DIRECTION_SHIFT));
        }
    }

    private void applyDelayedQueue(final long[] queue, final int length, final boolean useLevel) {
        final ScalarSkyEngine engine = this.engine;
        final int decodeOffsetX = -engine.encodeOffsetX;
        final int decodeOffsetY = -engine.encodeOffsetY;
        final int decodeOffsetZ = -engine.encodeOffsetZ;
        for (int index = 0; index < length; ++index) {
            final long queueValue = queue[index];
            final int positionX = ((int) queueValue & 63) + decodeOffsetX;
            final int positionZ = (((int) queueValue >>> 6) & 63) + decodeOffsetZ;
            final int positionY = (((int) queueValue >>> 12) & 0xFFFF) + decodeOffsetY;
            final int level = useLevel
                    ? (int) ((queueValue >>> PulsarEngine.LIGHT_LEVEL_SHIFT) & 15) : 0;
            engine.setLightLevel(positionX, positionY, positionZ, level);
        }
    }
}
