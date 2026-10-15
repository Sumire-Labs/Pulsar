package com.sumirelabs.pulsar.light;

/**
 * Pure light-array checks shared by packet integration and unit tests.
 */
final class PacketLightSection {

    private PacketLightSection() {
    }

    static boolean needsAbsentStorage(final boolean fullChunk, final byte[] blockLight) {
        // A full packet resets omitted slots. A partial packet leaves them untouched,
        // so even default light needs an explicit payload to erase stale client values.
        return !fullChunk || !isFilled(blockLight, (byte) 0);
    }

    static boolean isTrivial(final boolean blockEmpty, final byte[] blockLight,
                             final byte[] skyLight, final boolean writeSkylight) {
        if (!blockEmpty || !isFilled(blockLight, (byte) 0x00)) {
            return false;
        }
        return !writeSkylight || isFilled(skyLight, (byte) 0xFF);
    }

    private static boolean isFilled(final byte[] values, final byte expected) {
        if (values == null) {
            return true;
        }
        for (final byte value : values) {
            if (value != expected) {
                return false;
            }
        }
        return true;
    }
}
