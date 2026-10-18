package com.sumirelabs.pulsar.light;

import net.minecraft.block.BlockSlab;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.IBlockAccess;

/** Samples the exposed side of slab/stair surfaces and neighboring cube faces. */
public final class PartialFaceLight {
    private static final double EPSILON = 0.0001;
    private static final ThreadLocal<PartialFaceLight> LOCAL = ThreadLocal.withInitial(PartialFaceLight::new);
    private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();
    private final IBlockState[] states = new IBlockState[4];
    private final int[] positions = new int[12];
    private final int[] light = new int[4];

    private PartialFaceLight() {}

    public static boolean applies(IBlockState state) {
        return !state.isFullCube() && (state.getBlock() instanceof BlockSlab || state.getBlock() instanceof BlockStairs);
    }

    /** A cube face against a stair/slab is only partly visible and needs the same sampling. */
    public static boolean applies(IBlockAccess world, IBlockState state, BlockPos pos, EnumFacing normal) {
        if (applies(state)) return true;
        if (!state.isOpaqueCube()) return false;
        BlockPos.MutableBlockPos neighbor = LOCAL.get().neighbor;
        neighbor.setPos(pos.getX() + normal.getXOffset(), pos.getY() + normal.getYOffset(), pos.getZ() + normal.getZOffset());
        return applies(world.getBlockState(neighbor));
    }

    public static int sample(IBlockAccess world, IBlockState rendered, BlockPos origin, EnumFacing normal,
                             float x, float y, float z, int original) {
        // Callers check applies once per face (or per Forge vertex).
        return LOCAL.get().sampleVertex(world, rendered, origin, normal, x, y, z, original);
    }

    private int sampleVertex(IBlockAccess world, IBlockState rendered, BlockPos origin, EnumFacing normal,
                              float x, float y, float z, int original) {
        int axis = normal.getAxis() == EnumFacing.Axis.X ? 0 : normal.getAxis() == EnumFacing.Axis.Y ? 1 : 2;
        int u = axis == 0 ? 1 : 0, v = axis == 2 ? 1 : 2;
        int solid = 0, seeds = 0;
        for (int node = 0; node < 4; node++) {
            double px = origin.getX() + x + normal.getXOffset() * EPSILON;
            double py = origin.getY() + y + normal.getYOffset() * EPSILON;
            double pz = origin.getZ() + z + normal.getZOffset() * EPSILON;
            double du = (node & 1) == 0 ? -EPSILON : EPSILON;
            double dv = (node & 2) == 0 ? -EPSILON : EPSILON;
            if (u == 0) px += du; else py += du;
            if (v == 1) py += dv; else pz += dv;
            int bx = MathHelper.floor(px), by = MathHelper.floor(py), bz = MathHelper.floor(pz);
            int index = node * 3;
            positions[index] = bx; positions[index + 1] = by; positions[index + 2] = bz;
            scratch.setPos(bx, by, bz);
            boolean ownCell = bx == origin.getX() && by == origin.getY() && bz == origin.getZ();
            IBlockState state = ownCell
                    ? rendered : world.getBlockState(scratch);
            states[node] = state;
            if (solidAt(world, state, scratch, normal, px - bx, py - by, pz - bz, ownCell)) {
                solid |= 1 << node;
            } else {
                int su = u == 0 ? bx : by, sv = v == 1 ? by : bz;
                int ou = u == 0 ? origin.getX() : origin.getY(), ov = v == 1 ? origin.getY() : origin.getZ();
                if (su == ou && sv == ov) seeds |= 1 << node;
            }
        }
        // Vertices hidden by a stair/slab (including an adjacent block) still
        // influence the visible part of the quad through interpolation.
        if (seeds == 0) return world.getCombinedLight(origin, rendered.getLightValue(world, origin));
        int reachable = seeds;
        for (int step = 0; step < 3; step++) {
            int adjacent = ((reachable & 5) << 1) | ((reachable & 10) >> 1)
                    | ((reachable & 3) << 2) | ((reachable & 12) >> 2);
            reachable |= adjacent & ~solid & 15;
        }
        int fallback = 0;
        for (int node = 0; node < 4; node++) {
            int index = node * 3;
            scratch.setPos(positions[index], positions[index + 1], positions[index + 2]);
            int emission = states[node].getLightValue(world, scratch);
            boolean exposedEmitter = emission > 0 && (reachable & ((1 << (node ^ 1)) | (1 << (node ^ 2)))) != 0;
            light[node] = ((reachable & (1 << node)) != 0 || exposedEmitter)
                    ? world.getCombinedLight(scratch, emission) : -1;
            if ((seeds & (1 << node)) != 0) fallback = light[node];
        }
        int block = 0, sky = 0;
        for (int value : light) {
            if (value < 0) value = fallback;
            block += value & 0xffff;
            sky += value >>> 16;
        }
        return (block / 4) | ((sky / 4) << 16);
    }

    private static boolean solidAt(IBlockAccess world, IBlockState state, BlockPos pos, EnumFacing normal,
                                     double x, double y, double z, boolean actualState) {
        if (state.getLightOpacity(world, pos) < 15) return false;
        if (state.isOpaqueCube()) return true;
        if (state.getBlock() instanceof BlockSlab) {
            return (y >= .5) == (state.getValue(BlockSlab.HALF) == BlockSlab.EnumBlockHalf.TOP);
        }
        if (state.getBlock() instanceof BlockStairs) {
            return SmoothLightOcclusion.stairCorner(actualState ? state : state.getActualState(world, pos),
                    x >= .5 ? 1 : 0, y >= .5 ? 1 : 0, z >= .5 ? 1 : 0);
        }
        return state.isSideSolid(world, pos, normal.getOpposite());
    }
}
