package com.sumirelabs.pulsar.util;

/**
 * Section bounds for light propagation.
 *
 * <p>Vanilla 1.12.2 worlds always have block sections {@code 0..15} (16 sub
 * chunks of height 16). The constants here mirror that. Cubic Chunks support
 * is out of scope for the initial Pulsar release; if added later,
 * {@link #setBounds(int, int)} can be called during world init to widen the
 * range.
 */
public final class WorldUtil {

    private WorldUtil() {}

    private static int minSection = 0;
    private static int maxSection = 15;

    public static void setBounds(int min, int max) {
        minSection = min;
        maxSection = max;
    }

    public static int getMinSection() {
        return minSection;
    }

    public static int getMaxSection() {
        return maxSection;
    }

    /** Light sections extend one beyond block sections in each direction. */
    public static int getMinLightSection() {
        return minSection - 1;
    }

    public static int getMaxLightSection() {
        return maxSection + 1;
    }

    public static int getTotalSections() {
        return maxSection - minSection + 1;
    }

    public static int getTotalLightSections() {
        return getMaxLightSection() - getMinLightSection() + 1;
    }

    public static int getMinBlockY() {
        return minSection << 4;
    }

    public static int getMaxBlockY() {
        return (maxSection << 4) | 15;
    }
}
