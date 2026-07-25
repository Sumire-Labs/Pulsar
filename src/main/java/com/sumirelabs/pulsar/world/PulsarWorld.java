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
}
