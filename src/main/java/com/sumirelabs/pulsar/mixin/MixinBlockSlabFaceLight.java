package com.sumirelabs.pulsar.mixin;

import net.minecraft.block.BlockSlab;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Slabs take neighbour brightness only through their OPEN vertical face
 * (top slab → below, bottom slab → above).
 */
@Mixin(BlockSlab.class)
public abstract class MixinBlockSlabFaceLight extends MixinBlockFaceLight {

    @Override
    public boolean pulsar$useNeighborBrightness(final IBlockState state, final EnumFacing facing,
                                                final IBlockAccess access, final BlockPos pos) {
        if (facing.getAxis() != EnumFacing.Axis.Y) {
            return false;
        }
        if (((BlockSlab) (Object) this).isFullCube(state)) {
            return false;
        }
        return facing == (state.getValue(BlockSlab.HALF) == BlockSlab.EnumBlockHalf.TOP
                ? EnumFacing.DOWN : EnumFacing.UP);
    }
}
