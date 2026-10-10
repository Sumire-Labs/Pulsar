package com.sumirelabs.pulsar.light.engine;

/** Apply source-face exclusions once per queue entry, before selecting the ordered direction table. */
final class PropagationDirections {
    private PropagationDirections() {}

    static int allowedMask(final int directions, final int blockedFaces) {
        return (directions & 63) & ~blockedFaces;
    }
}
