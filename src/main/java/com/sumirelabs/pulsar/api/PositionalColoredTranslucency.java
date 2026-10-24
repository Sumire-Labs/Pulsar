package com.sumirelabs.pulsar.api;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;

/** World-thread, once-per-tick transmittance for loaded dynamic positions.
 * Lighting workers never invoke this callback or inspect its TileEntity.
 */
public interface PositionalColoredTranslucency extends ColoredTranslucency {
    @Override default int getColoredTransmittance(IBlockState state) { return 0xFFF; }
    int getColoredTransmittance(IBlockAccess world, BlockPos pos, IBlockState state);
}
