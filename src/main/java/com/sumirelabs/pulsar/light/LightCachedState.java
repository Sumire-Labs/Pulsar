package com.sumirelabs.pulsar.light;

/**
 * Implemented on {@code BlockStateContainer$StateImplementation} by
 * {@code MixinBlockStateImplementation}. Carries the lazily-memoised packed
 * light info for the state (see
 * {@link com.sumirelabs.pulsar.light.engine.LightInfo} for the bit layout).
 *
 * <p>This is the 1.12.2 analogue of Starlight's {@code BlockStateBaseMixin}
 * per-state {@code opacityIfCached}: it turns the per-BFS-visit virtual
 * {@code getLightOpacity()}/{@code getLightValue()} calls and the
 * {@code FaceOcclusion} hash lookups into a single field read.
 */
public interface LightCachedState {

    /**
     * @return the packed light info for this state, computing and memoising
     *         it on first use. Never 0 once computed (the COMPUTED bit is set).
     */
    int pulsar$lightInfo();
}
