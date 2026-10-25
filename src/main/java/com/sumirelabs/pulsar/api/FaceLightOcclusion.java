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
 * net.minecraft.util.math.BlockPos)}.
 *
 * <p>This directional opacity applies to scalar lighting and all RGB channels.
 * Use {@link ColoredTranslucency} or {@link BlockLightFilters} for additional
 * per-channel absorption; these filters cannot open an opaque face.
 * Implementations must depend only on the supplied state and direction, be
 * safe to call from lighting workers, and never access a live world.
 */
public interface FaceLightOcclusion {

    /**
     * @param state     the block state being queried
     * @param direction the face being queried (never {@code null})
     * @return 0–15 light opacity for that face. {@code 0} = transparent,
     * {@code 15} = fully opaque.
     */
    int getDirectionalLightOpacity(IBlockState state, EnumFacing direction);
}
