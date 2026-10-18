package com.sumirelabs.pulsar.api;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;

/** Position-dependent source color. Invoked on the world thread; workers consume immutable
 * samples. Loaded dynamic positions are sampled each tick, including color-only changes.
 */
public interface PositionalColoredLightSource extends ColoredLightSource {
    @Override default int getColoredLightEmission(IBlockState state) { return 0xFFF; }
    int getColoredLightEmission(IBlockAccess world, BlockPos pos, IBlockState state);
}
