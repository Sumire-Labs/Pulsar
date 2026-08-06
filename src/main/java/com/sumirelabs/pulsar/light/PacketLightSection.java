package com.sumirelabs.pulsar.light;

/** Pure light-array checks shared by packet integration and unit tests. */
final class PacketLightSection {

    private PacketLightSection() {
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
