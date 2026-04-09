package com.sumirelabs.pulsar.world;

import com.sumirelabs.pulsar.api.ExtendedWorld;
import com.sumirelabs.pulsar.light.WorldLightManager;

/**
 * Internal extension of {@link ExtendedWorld} exposing engine internals.
 * Mixed into {@code net.minecraft.world.World} by
 * {@code com.sumirelabs.pulsar.mixin.MixinWorld}.
 */
public interface PulsarWorld extends ExtendedWorld {

    WorldLightManager pulsar$getLightManager();

    void pulsar$shutdown();

    /**
     * Marks whether the local player is currently in the middle of a block
     * placement or break. The renderer uses this to fast-path certain light
     * updates so the visible result tracks the player action even before the
     * BFS engine has finished propagating.
     */
    void pulsar$setPlayerAction(boolean value);

    boolean pulsar$isPlayerAction();
}
