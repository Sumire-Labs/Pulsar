package com.sumirelabs.pulsar.api;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;

/**
 * Raw per-type light lookup WITHOUT the vanilla neighbour-brightness
 * indirection. Implemented on every {@code IBlockAccess} that the
 * facing-aware neighbour-brightness path ({@link FaceLitBlock}) runs
 * against: {@code World} and {@code ChunkCache}.
 */
public interface RawLightAccess {

    int pulsar$getRawLight(EnumSkyBlock lightType, BlockPos pos);
}
