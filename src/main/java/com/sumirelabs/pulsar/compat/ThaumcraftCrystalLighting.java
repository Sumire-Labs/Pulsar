package com.sumirelabs.pulsar.compat;

/** Startup-frozen scalar emission policy for Thaumcraft's placed vis crystals. */
public final class ThaumcraftCrystalLighting {

    private static int minimumLight;

    private ThaumcraftCrystalLighting() {
    }

    /** Called during postInit, before any world or lighting worker is started. */
    public static void initialize(final boolean thaumcraftLoaded, final int configuredMinimum) {
        minimumLight = thaumcraftLoaded ? Math.max(0, Math.min(15, configuredMinimum)) : 0;
    }

    /** Preserve higher emission supplied by Thaumcraft or another compatibility mod. */
    public static int emission(final int original) {
        return minimumLight == 0 ? original : Math.max(original, minimumLight);
    }

    /** Persisted with light caches so changing this setting forces a relight. */
    public static int cacheProfile() {
        return minimumLight;
    }
}
