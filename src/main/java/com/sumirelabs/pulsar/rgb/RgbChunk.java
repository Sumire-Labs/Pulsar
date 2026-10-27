package com.sumirelabs.pulsar.rgb;

/** Chunk-owned data; no global chunk cache survives a world unload. */
public interface RgbChunk {
    default int pulsar$getSavedPaletteGeneration() { return -1; }
    default void pulsar$setSavedPaletteGeneration(int generation) {}
    RgbStorage pulsar$getRgbStorage();
    void pulsar$setRgbStorage(RgbStorage storage);
    RgbSnapshot pulsar$getRgbSnapshot();
    void pulsar$setRgbSnapshot(RgbSnapshot snapshot);
    RgbStorage pulsar$getRgbSkyStorage();
    void pulsar$setRgbSkyStorage(RgbStorage storage);
    RgbSnapshot pulsar$getRgbSkySnapshot();
    void pulsar$setRgbSkySnapshot(RgbSnapshot snapshot);
}
