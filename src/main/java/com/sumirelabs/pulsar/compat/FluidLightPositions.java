package com.sumirelabs.pulsar.compat;

import git.jbredwards.fluidlogged_api.api.capability.IFluidStateCapability;
import git.jbredwards.fluidlogged_api.api.capability.IFluidStateContainer;
import git.jbredwards.fluidlogged_api.api.util.FluidState;
import git.jbredwards.fluidlogged_api.mod.common.capability.FluidStateCapabilityVanilla;

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Sparse, optional-API position enumeration kept separate from Loader startup. */
final class FluidLightPositions {
    private FluidLightPositions() {}

    /**
     * Returns packed local-cell keys, an empty array when there are no fluids,
     * or {@code null} when the index cannot be trusted and a full scan is needed.
     */
    static int[] enumerate(final IFluidStateCapability capability,
                           final int minSection, final int maxSection) {
        if (capability == null) return new int[0];
        try {
            // Only the vanilla implementation's serialized-position set is
            // known to enumerate every stored fluid. Custom capabilities and
            // containers must retain the caller's conservative full scan.
            if (capability.getClass() != FluidStateCapabilityVanilla.class) return null;
            final Set<IFluidStateContainer> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            int[] positions = new int[16];
            int count = 0;
            for (int sectionY = minSection; sectionY <= maxSection; sectionY++) {
                final int minY = sectionY << 4;
                final IFluidStateContainer container = capability.getContainer(minY);
                if (container == null) return null;
                if (container.getClass() != FluidStateCapabilityVanilla.class) return null;
                // Vanilla's serializer only represents Y 0..255; cubic or
                // custom-height containers may provide a wider mapping.
                // Require an exact round trip for the whole section before
                // trusting its sparse index, otherwise force the safe scan.
                if (container.deserializeY((char) container.serializeY(minY)) != minY
                        || container.deserializeY((char) container.serializeY(minY + 15)) != minY + 15) {
                    return null;
                }
                if (!seen.add(container)) continue;
                for (final char position : container.getSerializedPositions()) {
                    if (!container.hasFluidState(position)) continue;
                    final FluidState fluid = container.getFluidState(position, FluidState.EMPTY);
                    if (fluid.isEmpty()) continue;
                    final int y = container.deserializeY(position);
                    final int x = container.deserializeX(position) & 15;
                    final int z = container.deserializeZ(position) & 15;
                    if (count == positions.length) positions = Arrays.copyOf(positions, positions.length << 1);
                    positions[count++] = (y << 8) | (z << 4) | x;
                }
            }
            return count == positions.length ? positions : Arrays.copyOf(positions, count);
        } catch (final LinkageError | RuntimeException unavailable) {
            // Mod versions with a different container implementation retain
            // correctness through ContextualLightManager's full-scan fallback.
            return null;
        }
    }

    /**
     * Whether a known vanilla capability contains any fluids in the chunk.
     * Unknown implementations or height serializers conservatively mean true.
     */
    static boolean mayHaveFluidStates(final IFluidStateCapability capability,
                                      final int minSection, final int maxSection) {
        if (capability == null) return false;
        try {
            // Only this implementation's position index is known to cover the
            // full capability consistently; wrappers and alternate APIs scan.
            if (capability.getClass() != FluidStateCapabilityVanilla.class) return true;
            final IFluidStateContainer shared = capability.getContainer(minSection << 4);
            if (shared == null || shared.getClass() != FluidStateCapabilityVanilla.class) return true;
            for (int sectionY = minSection; sectionY <= maxSection; sectionY++) {
                final int minY = sectionY << 4;
                final int maxY = minY + 15;
                final IFluidStateContainer container = capability.getContainer(minY);
                if (container != shared
                        || container.deserializeY((char) container.serializeY(minY)) != minY
                        || container.deserializeY((char) container.serializeY(maxY)) != maxY) {
                    return true;
                }
            }
            return !shared.getSerializedPositions().isEmpty();
        } catch (final LinkageError | RuntimeException unavailable) {
            return true;
        }
    }
}
