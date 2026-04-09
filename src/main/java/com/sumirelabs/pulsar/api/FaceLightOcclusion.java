package com.sumirelabs.pulsar.api;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;

/**
 * Implemented by blocks whose light opacity depends on which face is being
 * queried (e.g. partial cubes that block light from one direction but not
 * another).
 *
 * <p>Pulsar's BFS engine consults this interface before falling back to the
 * scalar opacity returned by
 * {@link IBlockState#getLightOpacity(net.minecraft.world.IBlockAccess,
 *        net.minecraft.util.math.BlockPos)}.
 *
 * <p>This API surface is intentionally minimal: Pulsar's initial release is
 * scalar-only (no RGB), so there is no per-channel absorption variant. RGB
 * additions can append a sibling interface in a future release.
 */
public interface FaceLightOcclusion {

    /**
     * @param state     the block state being queried
     * @param direction the face being queried (never {@code null})
     * @return 0–15 light opacity for that face. {@code 0} = transparent,
     *         {@code 15} = fully opaque.
     */
    int getDirectionalLightOpacity(IBlockState state, EnumFacing direction);
}
