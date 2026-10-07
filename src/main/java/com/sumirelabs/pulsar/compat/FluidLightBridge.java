package com.sumirelabs.pulsar.compat;

import com.sumirelabs.pulsar.light.engine.LightInfo;
import git.jbredwards.fluidlogged_api.api.capability.IFluidStateCapability;
import git.jbredwards.fluidlogged_api.api.capability.IFluidStateContainer;
import git.jbredwards.fluidlogged_api.api.util.FluidState;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.fml.common.Loader;

/**
 * Fluidlogged API integration: makes the engine see the fluid stored in a
 * fluidlogged block. Fluidlogged keeps that fluid in a per-chunk capability
 * (invisible to per-state caches) and patches the VANILLA engine to use
 * {@code max(block, fluid)} for opacity and light value — this bridge applies
 * the identical formula to Pulsar's packed {@link LightInfo} reads, so
 * results match vanilla-with-Fluidlogged semantics. (Alfheim's merged
 * integration uses the same max() in its {@code LightUtil}.)
 *
 * <p>Classloading: Fluidlogged types are referenced only inside branches
 * guarded by {@link #LOADED}, so this class is safe to load when the mod is
 * absent (same pattern Alfheim ships).
 *
 * <p>Threading: worker-side reads land on Fluidlogged's plain
 * {@code FluidState[256]} layers — reference reads are atomic, and any
 * concurrent fluidlog fires {@code world.checkLight}, which re-schedules the
 * engine; transient staleness self-heals exactly like live nibble reads.
 */
public final class FluidLightBridge {

    public static final boolean LOADED = Loader.isModLoaded("fluidlogged_api");

    private FluidLightBridge() {
    }

    /**
     * The chunk's fluid capability as an opaque handle, or {@code null}.
     */
    public static Object capabilityOf(final Chunk chunk) {
        if (!LOADED || chunk == null) {
            return null;
        }
        return IFluidStateCapability.get(chunk);
    }

    /** Main-thread snapshot source; null means no stored fluid at this cell. */
    public static IBlockState stateAt(final Chunk chunk, final int x, final int y, final int z) {
        if (!LOADED) return null;
        final IFluidStateCapability capability = IFluidStateCapability.get(chunk);
        if (capability == null) return null;
        final IFluidStateContainer container = capability.getContainer(y);
        if (container == null) return null;
        final FluidState fluid = container.getFluidState(x, y, z, FluidState.EMPTY);
        return fluid.isEmpty() ? null : fluid.getState();
    }

    /**
     * Returns the sparse set of fluid-bearing block positions as Pulsar's packed
     * local-cell keys. An empty array means the chunk has no fluid capability or
     * no stored fluids; {@code null} means this API implementation could not be
     * enumerated safely and the caller must use its conservative scan.
     */
    public static int[] fluidPositions(final Chunk chunk, final int minSection, final int maxSection) {
        if (!LOADED || chunk == null) return new int[0];
        try {
            return FluidLightPositions.enumerate(
                    IFluidStateCapability.get(chunk), minSection, maxSection);
        } catch (final LinkageError | RuntimeException unavailable) {
            return null;
        }
    }

    /**
     * True when a fluid may be present or the optional API index cannot be
     * trusted. Called by the lighting worker before its initial source scan.
     * Fluidlogged queues checkLight after mutating this index, so any task that
     * races an insertion gets a later per-position correction through the same
     * synchronized light queue.
     */
    public static boolean mayHaveFluidStates(final Object capability,
                                             final int minSection, final int maxSection) {
        if (!LOADED) return false;
        if (capability == null) return false;
        try {
            return FluidLightPositions.mayHaveFluidStates(
                    (IFluidStateCapability) capability, minSection, maxSection);
        } catch (final LinkageError | RuntimeException unavailable) {
            return true;
        }
    }

    /**
     * Max the fluid's opacity/emission at (x, y, z) into packed
     * {@link LightInfo} bits. The fluid fills the whole block space, so the
     * combined cell becomes a plain uniform absorber (face bits dropped) —
     * the same scalar semantics Fluidlogged patches into vanilla.
     */
    public static int merge(final int info, final Object capability, final int x, final int y, final int z,
                            final IBlockAccess access, final BlockPos.MutableBlockPos lookupPos) {
        final IFluidStateContainer container = ((IFluidStateCapability) capability).getContainer(y);
        if (container == null) return info;
        final FluidState fluid = container.getFluidState(x, y, z, FluidState.EMPTY);
        if (fluid.isEmpty()) {
            return info;
        }
        final IBlockState fluidState = fluid.getState();
        final int fluidInfo = LightInfo.resolveContextual(
                LightInfo.of(fluidState), fluidState, access, lookupPos, x, y, z);
        final int opacity = Math.max(info & LightInfo.OPACITY_MASK, fluidInfo & LightInfo.OPACITY_MASK);
        final int emission = Math.max(LightInfo.emission(info), LightInfo.emission(fluidInfo));
        return LightInfo.COMPUTED | opacity | (emission << LightInfo.EMISSION_SHIFT);
    }

    /**
     * Scalar opacity with the fluid layer max'd in for the heightmap walks in
     * {@code MixinChunk}. Context-sensitive fluid blocks use the same world
     * position as the containing block.
     */
    public static int maxOpacityAt(final Chunk chunk, final int blockOpacity,
                                   final int x, final int y, final int z,
                                   final BlockPos.MutableBlockPos lookupPos) {
        if (!LOADED) {
            return blockOpacity;
        }
        final IFluidStateCapability cap = IFluidStateCapability.get(chunk);
        if (cap == null) {
            return blockOpacity;
        }
        final IFluidStateContainer container = cap.getContainer(y);
        if (container == null) return blockOpacity;
        final FluidState fluid = container.getFluidState(x, y, z, FluidState.EMPTY);
        if (fluid.isEmpty()) {
            return blockOpacity;
        }
        final IBlockState fluidState = fluid.getState();
        final int worldX = (chunk.x << 4) + x;
        final int worldZ = (chunk.z << 4) + z;
        final int fluidInfo = LightInfo.resolveContextual(
                LightInfo.of(fluidState), fluidState, chunk.getWorld(), lookupPos, worldX, y, worldZ);
        return Math.max(blockOpacity, LightInfo.opacity(fluidInfo));
    }
}
