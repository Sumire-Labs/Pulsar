package com.sumirelabs.pulsar.mixin;

import net.minecraft.block.BlockStairs;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Stairs take neighbour brightness only through their OPEN vertical face
 * (top half → below, bottom half → above). Ported from Alfheim's
 * {@code BlockStairsMixin} (MIT, Red Studio / Desoroxxx).
 */
@Mixin(BlockStairs.class)
public abstract class MixinBlockStairsFaceLight extends MixinBlockFaceLight {

    @Override
    public boolean pulsar$useNeighborBrightness(final IBlockState state, final EnumFacing facing,
                                                final IBlockAccess access, final BlockPos pos) {
        if (facing.getAxis() != EnumFacing.Axis.Y) {
            return false;
        }
        return facing == (state.getValue(BlockStairs.HALF) == BlockStairs.EnumHalf.TOP
                ? EnumFacing.DOWN : EnumFacing.UP);
    }
}
