package com.sumirelabs.pulsar.api;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.IBlockAccess;

/**
 * Facing-aware neighbour-brightness contract attached to {@code Block}
 * (MC-92 family: slab/stairs faces lit by the max of ALL neighbours in
 * vanilla instead of the face-appropriate one). Ported from Alfheim's
 * {@code ILitBlock} (MIT, Red Studio / Desoroxxx).
 */
public interface FaceLitBlock {

    int pulsar$getLightFor(IBlockState state, IBlockAccess access, EnumSkyBlock lightType, BlockPos pos);

    boolean pulsar$useNeighborBrightness(IBlockState state, EnumFacing facing, IBlockAccess access, BlockPos pos);

    int pulsar$getLightOpacity(IBlockState state, EnumFacing facing, IBlockAccess access, BlockPos pos);
}
