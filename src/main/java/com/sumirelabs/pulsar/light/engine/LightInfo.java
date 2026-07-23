package com.sumirelabs.pulsar.light.engine;

import com.sumirelabs.pulsar.api.FaceLightOcclusion;
import com.sumirelabs.pulsar.light.LightCachedState;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;

/**
 * Packed per-{@link IBlockState} light attributes, memoised on the state
 * object via {@link LightCachedState}. Everything the BFS hot loops need per
 * neighbour visit collapses to one {@code int}:
 *
 * <pre>
 *   bits  0..3  : opacity, clamped to [0, 15] (light levels cap at 15, so
 *                 clamping vanilla's 0-255 range preserves all light math)
 *   bits  4..7  : emission (getLightValue() &amp; 0xF, as the engines always did)
 *   bit   8     : REGISTRY — block is in FaceOcclusion's sided-transparency set
 *   bit   9     : DYNAMIC — block implements {@link FaceLightOcclusion}
 *   bit  10     : SIDED — REGISTRY &amp;&amp; opacity &gt; 1 (the state-level gate the
 *                 calculate paths used)
 *   bits 16..21 : face-solid bits by AxisDirection ordinal (only meaningful
 *                 when REGISTRY; all-solid for DYNAMIC, matching the null-table
 *                 fallback of FaceOcclusion.isFaceSolid)
 *   bit  30     : COMPUTED — sentinel so 0 means "not yet computed"
 * </pre>
 *
 * <p>Caching assumes the non-context {@code getLightOpacity()} /
 * {@code getLightValue()} overloads are constant per state — the same
 * assumption Pulsar's hot path has always made by calling them without
 * world/pos context.
 */
public final class LightInfo {

    public static final int OPACITY_MASK = 0xF;
    public static final int EMISSION_SHIFT = 4;
    public static final int REGISTRY = 1 << 8;
    public static final int DYNAMIC = 1 << 9;
    public static final int SIDED = 1 << 10;
    public static final int FACE_SHIFT = 16;
    public static final int FACE_MASK = 0x3F;
    public static final int COMPUTED = 1 << 30;

    private LightInfo() {}

    /** Look up (or lazily compute) the packed info for a state. */
    public static int of(final IBlockState state) {
        if (state instanceof LightCachedState) {
            return ((LightCachedState) state).pulsar$lightInfo();
        }
        // Exotic IBlockState implementation that is not a
        // BlockStateContainer$StateImplementation subclass: compute uncached.
        return compute(state);
    }

    @SuppressWarnings("deprecation")
    public static int compute(final IBlockState state) {
        final Block block = state.getBlock();
        final int opacity = Math.min(15, Math.max(0, state.getLightOpacity()));
        final int emission = state.getLightValue() & 0xF;
        int info = COMPUTED | opacity | (emission << EMISSION_SHIFT);

        final boolean dynamic = block instanceof FaceLightOcclusion;
        if (dynamic) {
            info |= DYNAMIC;
        }
        if (FaceOcclusion.hasSidedTransparency(block)) {
            info |= REGISTRY;
            if (opacity > 1) {
                info |= SIDED;
            }
            int faceBits;
            if (dynamic) {
                // FaceOcclusion.isFaceSolid has no table for interface blocks
                // and reports every face solid; preserve that behaviour.
                faceBits = FACE_MASK;
            } else {
                final int meta = block.getMetaFromState(state);
                faceBits = 0;
                for (int dir = 0; dir < 6; ++dir) {
                    if (FaceOcclusion.isFaceSolid(block, meta, dir)) {
                        faceBits |= 1 << dir;
                    }
                }
            }
            info |= faceBits << FACE_SHIFT;
        }
        return info;
    }

    public static int opacity(final int info) {
        return info & OPACITY_MASK;
    }

    public static int emission(final int info) {
        return (info >>> EMISSION_SHIFT) & 0xF;
    }

    public static boolean isFaceSolid(final int info, final int dirOrdinal) {
        return (info & (1 << (FACE_SHIFT + dirOrdinal))) != 0;
    }

    /** Face-solid bitmask by AxisDirection ordinal; 0 when not REGISTRY. */
    public static int faceBits(final int info) {
        return (info >>> FACE_SHIFT) & FACE_MASK;
    }

    /**
     * Scalar absorption (1-15) for light entering {@code state} through the
     * face given by {@code dirOrdinal}. Identical semantics to
     * {@link FaceOcclusion#resolveScalarAbsorption} but sourced from the
     * packed info; only DYNAMIC blocks still dispatch to the interface.
     */
    public static int absorption(final int info, final IBlockState state, final int dirOrdinal) {
        if ((info & DYNAMIC) != 0) {
            return FaceOcclusion.resolveScalarAbsorption(state, dirOrdinal);
        }
        final int opacity = info & OPACITY_MASK;
        if ((info & SIDED) != 0) {
            return isFaceSolid(info, dirOrdinal) ? opacity : 1;
        }
        return Math.max(1, opacity);
    }
}
