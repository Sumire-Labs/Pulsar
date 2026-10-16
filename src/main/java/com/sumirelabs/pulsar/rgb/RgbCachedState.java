package com.sumirelabs.pulsar.rgb;

/** Static chromaticity/filter metadata only; world-dependent light stays uncached. */
public interface RgbCachedState {
    int pulsar$rgbProperties();
}
