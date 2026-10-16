package com.sumirelabs.pulsar.rgb;

/** Chunk-owned data; no global chunk cache survives a world unload. */
public interface RgbChunk {
    RgbStorage pulsar$getRgbStorage();
    void pulsar$setRgbStorage(RgbStorage storage);
    RgbSnapshot pulsar$getRgbSnapshot();
    void pulsar$setRgbSnapshot(RgbSnapshot snapshot);
}
