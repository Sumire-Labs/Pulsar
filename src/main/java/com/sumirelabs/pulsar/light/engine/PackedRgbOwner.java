package com.sumirelabs.pulsar.light.engine;

/** Release private halo planes after an initial/range operation, including failure paths. */
interface PackedRgbOwner {
    void finishRgbTask();
}
