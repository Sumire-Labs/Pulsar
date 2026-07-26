package com.sumirelabs.pulsar.api;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.IBlockAccess;

/**
 * State-level view of {@link FaceLitBlock} (attached to
 * {@code BlockStateContainer$StateImplementation}). Ported from Alfheim's
 * {@code ILightInfoProvider} (MIT, Red Studio / Desoroxxx).
 */
public interface FaceLightInfo {

    int pulsar$getLightFor(IBlockAccess access, EnumSkyBlock lightType, BlockPos pos);

    boolean pulsar$useNeighborBrightness(EnumFacing facing, IBlockAccess access, BlockPos pos);

    int pulsar$getLightOpacity(EnumFacing facing, IBlockAccess access, BlockPos pos);
}
