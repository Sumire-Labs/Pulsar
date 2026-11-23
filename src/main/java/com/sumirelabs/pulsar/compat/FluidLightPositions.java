/*
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.sumirelabs.pulsar.compat;

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import git.jbredwards.fluidlogged_api.api.capability.IFluidStateCapability;
import git.jbredwards.fluidlogged_api.api.capability.IFluidStateContainer;
import git.jbredwards.fluidlogged_api.api.util.FluidState;
import git.jbredwards.fluidlogged_api.mod.common.capability.FluidStateCapabilityVanilla;

/** Sparse, optional-API position enumeration kept separate from Loader startup. */
final class FluidLightPositions {

    private FluidLightPositions() {}

    // This version's NBT reader installs each fluid through setFluidState(char, FluidState).
    // A different serializer or container may not populate the hint.
    private static final class HintVersion {

        private static final net.minecraftforge.fml.common.ModContainer MOD = net.minecraftforge.fml.common.Loader
            .instance().getIndexedModList().get("fluidlogged_api");
        static final boolean SUPPORTED = MOD != null && "3.3.3".equals(MOD.getVersion());

    }

    /**
     * Returns packed local-cell keys, an empty array when there are no fluids,
     * or {@code null} when the index cannot be trusted and a full scan is needed.
     */
    static int[] enumerate(final IFluidStateCapability capability,
        final int minSection, final int maxSection) {
        if (capability == null)
            return new int[0];
        try {
            // Only the vanilla implementation's serialized-position set is
            // known to enumerate every stored fluid. Custom capabilities and
            // containers must retain the caller's conservative full scan.
            if (capability.getClass() != FluidStateCapabilityVanilla.class)
                return null;
            final Set<IFluidStateContainer> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            int[] positions = new int[16];
            int count = 0;
            for (int sectionY = minSection; sectionY <= maxSection; sectionY++) {
                final int minY = sectionY << 4;
                final IFluidStateContainer container = capability.getContainer(minY);
                if (container == null)
                    return null;
                if (container.getClass() != FluidStateCapabilityVanilla.class)
                    return null;
                // Vanilla's serializer only represents Y 0..255; cubic or
                // custom-height containers may provide a wider mapping.
                // Require an exact round trip for the whole section before
                // trusting its sparse index, otherwise force the safe scan.
                if (container.deserializeY((char) container.serializeY(minY)) != minY
                    || container.deserializeY((char) container.serializeY(minY + 15)) != minY + 15) {
                    return null;
                }
                if (!seen.add(container))
                    continue;
                for (final char position : container.getSerializedPositions()) {
                    if (!container.hasFluidState(position))
                        continue;
                    final FluidState fluid = container.getFluidState(position, FluidState.EMPTY);
                    if (fluid.isEmpty())
                        continue;
                    final int y = container.deserializeY(position);
                    final int x = container.deserializeX(position) & 15;
                    final int z = container.deserializeZ(position) & 15;
                    if (count == positions.length)
                        positions = Arrays.copyOf(positions, positions.length << 1);
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

    /** Zero for empty, one bit per vanilla section, or -1 when absence cannot be proven. */
    static int sourceScanSections(final IFluidStateCapability capability, final int minSection, final int maxSection) {
        if (capability == null)
            return 0;
        if (capability.getClass() != FluidStateCapabilityVanilla.class || minSection < 0 || maxSection > 15)
            return -1;
        final IFluidStateContainer shared = capability.getContainer(minSection << 4);
        if (shared != capability)
            return -1;
        for (int sy = minSection; sy <= maxSection; sy++) {
            int y = sy << 4;
            if (capability.getContainer(y) != shared || shared.deserializeY((char) shared.serializeY(y)) != y
                || shared.deserializeY((char) shared.serializeY(y + 15)) != y + 15)
                return -1;
        }
        if (shared.getSerializedPositions().isEmpty())
            return 0;
        if (shared instanceof FluidSectionHint hint && HintVersion.SUPPORTED) {
            int mask = hint.pulsar$fluidSections();
            if (mask != 0)
                return mask;
        }
        return -1;
    }

}
