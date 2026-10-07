package com.sumirelabs.pulsar.light;

/** Conservative load-time classification supplied by the vanilla section container mixin. */
public interface ContextualLightPalette {
    int MAY_EMIT = 1;
    int CONTEXT_OPACITY = 1 << 1;
    int CONTEXT_EMISSION = 1 << 2;
    int CONTEXT_MASK = CONTEXT_OPACITY | CONTEXT_EMISSION;
    int CONSERVATIVE_FLAGS = MAY_EMIT | CONTEXT_MASK;

    default int pulsar$lightPaletteFlags() {
        return CONSERVATIVE_FLAGS;
    }

    default boolean pulsar$needsContextualSamples() {
        return (this.pulsar$lightPaletteFlags() & CONTEXT_MASK) != 0;
    }
}
