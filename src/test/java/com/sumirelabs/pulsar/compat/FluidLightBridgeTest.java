package com.sumirelabs.pulsar.compat;

import it.unimi.dsi.fastutil.chars.CharOpenHashSet;
import it.unimi.dsi.fastutil.chars.CharSet;
import git.jbredwards.fluidlogged_api.api.capability.IFluidStateCapability;
import git.jbredwards.fluidlogged_api.api.util.FluidState;
import git.jbredwards.fluidlogged_api.mod.common.capability.FluidStateCapabilityVanilla;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class FluidLightBridgeTest {
    private static final class TestFluidState extends FluidState {
        TestFluidState() { super(null, Blocks.STONE.getDefaultState()); }
    }

    private static final class IncompleteFluidIndexCapability extends FluidStateCapabilityVanilla {
        IncompleteFluidIndexCapability(final int chunkX, final int chunkZ) {
            super(chunkX, chunkZ);
        }

        @Override
        public CharSet getSerializedPositions() {
            // Simulate a compatible-looking custom implementation whose index
            // omits a real fluid state without throwing an exception.
            return new CharOpenHashSet();
        }
    }

    @BeforeAll static void bootstrap() { Bootstrap.register(); }

    @Test
    void sparseIndexFindsFluidsInOtherwiseEmptySectionsAndUsesChunkLocalCoordinates() {
        final int chunkX = 5;
        final int chunkZ = -2;
        final FluidStateCapabilityVanilla capability = new FluidStateCapabilityVanilla(chunkX, chunkZ);
        assertFalse(FluidLightPositions.mayHaveFluidStates(capability, 0, 15));
        final FluidState water = new TestFluidState();
        capability.setFluidState((chunkX << 4) + 3, 42, (chunkZ << 4) + 7, water);
        assertTrue(FluidLightPositions.mayHaveFluidStates(capability, 0, 15));
        capability.setFluidState((chunkX << 4) + 12, 90, (chunkZ << 4) + 1, water);

        final int[] positions = FluidLightPositions.enumerate(capability, 0, 15);
        Arrays.sort(positions);
        assertArrayEquals(new int[]{(42 << 8) | (7 << 4) | 3, (90 << 8) | (1 << 4) | 12}, positions);

        capability.setFluidState((chunkX << 4) + 3, 42, (chunkZ << 4) + 7, FluidState.EMPTY);
        assertArrayEquals(new int[]{(90 << 8) | (1 << 4) | 12},
                FluidLightPositions.enumerate(capability, 0, 15));
        assertTrue(FluidLightPositions.mayHaveFluidStates(capability, 0, 15));
        capability.setFluidState((chunkX << 4) + 12, 90, (chunkZ << 4) + 1, FluidState.EMPTY);
        assertFalse(FluidLightPositions.mayHaveFluidStates(capability, 0, 15));
    }

    @Test
    void emptyFluidIndexIsDifferentFromAnUnavailableContainer() {
        final FluidStateCapabilityVanilla capability = new FluidStateCapabilityVanilla(0, 0);
        assertArrayEquals(new int[0], FluidLightPositions.enumerate(capability, 0, 15));
        assertFalse(FluidLightPositions.mayHaveFluidStates(capability, 0, 15));

        final IFluidStateCapability missingContainer = (IFluidStateCapability) Proxy.newProxyInstance(
                IFluidStateCapability.class.getClassLoader(), new Class<?>[]{IFluidStateCapability.class},
                (proxy, method, args) -> null);
        assertNull(FluidLightPositions.enumerate(missingContainer, 0, 0));
        assertTrue(FluidLightPositions.mayHaveFluidStates(missingContainer, 0, 0));
        assertTrue(FluidLightPositions.mayHaveFluidStates(
                new FluidStateCapabilityVanilla(0, 0) { }, 0, 15));
    }

    @Test
    void unknownCapabilityWithAnIncompletePositionIndexFallsBackToFullScan() {
        final int chunkX = -3;
        final int chunkZ = 4;
        final int worldX = (chunkX << 4) + 3;
        final int worldZ = (chunkZ << 4) + 7;
        final IncompleteFluidIndexCapability capability =
                new IncompleteFluidIndexCapability(chunkX, chunkZ);
        capability.setFluidState(worldX, 42, worldZ, new TestFluidState());

        assertFalse(capability.getFluidState(worldX, 42, worldZ, FluidState.EMPTY).isEmpty());
        assertTrue(capability.getSerializedPositions().isEmpty());
        assertNull(FluidLightPositions.enumerate(capability, 0, 15));
        assertTrue(FluidLightPositions.mayHaveFluidStates(capability, 0, 15));
    }

    @Test
    void vanillaSerializerFallsBackForHeightsItCannotRepresent() {
        final FluidStateCapabilityVanilla capability = new FluidStateCapabilityVanilla(0, 0);
        assertNull(FluidLightPositions.enumerate(capability, -1, 15));
        assertNull(FluidLightPositions.enumerate(capability, 16, 16));
        assertTrue(FluidLightPositions.mayHaveFluidStates(capability, -1, 15));
        assertTrue(FluidLightPositions.mayHaveFluidStates(capability, 16, 16));
    }
}
