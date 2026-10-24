package com.sumirelabs.pulsar.light;

import net.minecraft.block.BlockSlab;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;

/** Geometry at the shared vertex, rather than translucency in the next row of blocks. */
public final class SmoothLightOcclusion {
    // Edge order used by vanilla's EnumNeighborInfo and Celeritas's AoNeighborInfo.
    private static final EnumFacing[][] EDGES = {
            {EnumFacing.WEST, EnumFacing.EAST, EnumFacing.NORTH, EnumFacing.SOUTH},
            {EnumFacing.EAST, EnumFacing.WEST, EnumFacing.NORTH, EnumFacing.SOUTH},
            {EnumFacing.UP, EnumFacing.DOWN, EnumFacing.EAST, EnumFacing.WEST},
            {EnumFacing.WEST, EnumFacing.EAST, EnumFacing.DOWN, EnumFacing.UP},
            {EnumFacing.UP, EnumFacing.DOWN, EnumFacing.NORTH, EnumFacing.SOUTH},
            {EnumFacing.DOWN, EnumFacing.UP, EnumFacing.NORTH, EnumFacing.SOUTH}
    };
    private SmoothLightOcclusion() {}

    public static int cornerIndex(EnumFacing normal, int x, int y, int z) {
        EnumFacing[] edges = EDGES[normal.ordinal()];
        int a = edges[3].getAxis() == EnumFacing.Axis.X ? x : edges[3].getAxis() == EnumFacing.Axis.Y ? y : z;
        int b = edges[0].getAxis() == EnumFacing.Axis.X ? x : edges[0].getAxis() == EnumFacing.Axis.Y ? y : z;
        boolean a3 = a == (edges[3].getAxisDirection() == EnumFacing.AxisDirection.POSITIVE ? 1 : 0);
        boolean b0 = b == (edges[0].getAxisDirection() == EnumFacing.AxisDirection.POSITIVE ? 1 : 0);
        return b0 ? (a3 ? 0 : 1) : (a3 ? 3 : 2);
    }

    /** Excluding an occluded sample must never brighten a different channel. */
    public static int limitLight(int sample, int center) {
        return Math.min(sample & 0xffff, center & 0xffff) | (Math.min(sample >>> 16, center >>> 16) << 16);
    }

    /** Bits 0/1/2 replace the two edges and diagonal with the face-center light. */
    public static int hiddenSamples(IBlockAccess world, int x, int y, int z, EnumFacing normal,
                                    int corner, int emissiveMask, BlockPos.MutableBlockPos scratch) {
        EnumFacing[] edges = EDGES[normal.ordinal()];
        EnumFacing a = edges[corner == 0 || corner == 3 ? 3 : 2];
        EnumFacing b = edges[corner < 2 ? 0 : 1];
        scratch.setPos(x + a.getXOffset(), y + a.getYOffset(), z + a.getZOffset());
        boolean solidA = blocksCorner(world, scratch, normal, a.getOpposite(), b);
        int mask = solidA && (emissiveMask & 1) == 0 && world.getBlockState(scratch).getLightValue(world, scratch) == 0 ? 1 : 0;
        scratch.setPos(x + b.getXOffset(), y + b.getYOffset(), z + b.getZOffset());
        boolean solidB = blocksCorner(world, scratch, normal, b.getOpposite(), a);
        if (solidB && (emissiveMask & 2) == 0 && world.getBlockState(scratch).getLightValue(world, scratch) == 0) mask |= 2;
        if (solidA && solidB) return mask | 4;
        scratch.setPos(x + a.getXOffset() + b.getXOffset(), y + a.getYOffset() + b.getYOffset(),
                z + a.getZOffset() + b.getZOffset());
        if ((emissiveMask & 4) == 0 && blocksCorner(world, scratch, normal, a.getOpposite(), b.getOpposite())
                && world.getBlockState(scratch).getLightValue(world, scratch) == 0) mask |= 4;
        return mask;
    }

    private static boolean blocksCorner(IBlockAccess world, BlockPos pos, EnumFacing normal,
                                         EnumFacing towardCenter, EnumFacing towardDiagonal) {
        IBlockState state = world.getBlockState(pos);
        // Transparent materials and attenuating media must keep their existing light samples.
        if (state.getLightOpacity(world, pos) < 15) return false;
        if (state.isOpaqueCube()) return true;
        int x = cornerCoordinate(EnumFacing.Axis.X, normal, towardCenter, towardDiagonal);
        int y = cornerCoordinate(EnumFacing.Axis.Y, normal, towardCenter, towardDiagonal);
        int z = cornerCoordinate(EnumFacing.Axis.Z, normal, towardCenter, towardDiagonal);
        if (state.getBlock() instanceof BlockSlab) {
            return y == (state.getValue(BlockSlab.HALF) == BlockSlab.EnumBlockHalf.TOP ? 1 : 0);
        }
        if (state.getBlock() instanceof BlockStairs) {
            return stairCorner(state.getActualState(world, pos), x, y, z);
        }
        // A modded partial block's bounding box need not describe its holes. Only
        // trust a complete face rather than treating its enclosing box as solid.
        return state.isSideSolid(world, pos, normal.getOpposite());
    }

    private static int cornerCoordinate(EnumFacing.Axis axis, EnumFacing normal,
                                         EnumFacing center, EnumFacing diagonal) {
        EnumFacing edge = center.getAxis() == axis ? center : diagonal;
        if (normal.getAxis() == axis) return normal.getAxisDirection() == EnumFacing.AxisDirection.POSITIVE ? 0 : 1;
        return edge.getAxisDirection() == EnumFacing.AxisDirection.POSITIVE ? 1 : 0;
    }

    static boolean stairCorner(IBlockState state, int x, int y, int z) {
        if (y == (state.getValue(BlockStairs.HALF) == BlockStairs.EnumHalf.TOP ? 1 : 0)) return true;
        EnumFacing facing = state.getValue(BlockStairs.FACING);
        boolean front = halfContains(facing, x, z);
        return switch (state.getValue(BlockStairs.SHAPE)) {
            case STRAIGHT -> front;
            case OUTER_LEFT -> front && halfContains(facing.rotateYCCW(), x, z);
            case OUTER_RIGHT -> front && halfContains(facing.rotateY(), x, z);
            case INNER_LEFT -> front || halfContains(facing.rotateYCCW(), x, z);
            case INNER_RIGHT -> front || halfContains(facing.rotateY(), x, z);
        };
    }

    private static boolean halfContains(EnumFacing facing, int x, int z) {
        return (facing.getAxis() == EnumFacing.Axis.X ? x : z)
                == (facing.getAxisDirection() == EnumFacing.AxisDirection.POSITIVE ? 1 : 0);
    }

    /** Celeritas's enum is optional and must never be linked on a vanilla-only client. */
    public static EnumFacing celeritasFacing(Object direction) {
        return switch (((Enum<?>) direction).name()) {
            case "POS_X" -> EnumFacing.EAST;
            case "NEG_X" -> EnumFacing.WEST;
            case "POS_Y" -> EnumFacing.UP;
            case "NEG_Y" -> EnumFacing.DOWN;
            case "POS_Z" -> EnumFacing.SOUTH;
            case "NEG_Z" -> EnumFacing.NORTH;
            default -> throw new IllegalArgumentException("Not an axis direction: " + direction);
        };
    }
}
