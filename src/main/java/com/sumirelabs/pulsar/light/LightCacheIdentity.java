package com.sumirelabs.pulsar.light;

/** Scalar caches remain v10; extension caches use v11 so older builds reject them. */
public record LightCacheIdentity(String backendKey, int crystalProfile) {
    public static final int SCALAR_VERSION = 10;
    public static final int EXTENSION_VERSION = 11;
    public LightCacheIdentity {
        if (backendKey == null || backendKey.length() > 256 || !backendKey.matches("[a-zA-Z0-9_.:/=-]*"))
            throw new IllegalArgumentException("Invalid lighting backend cache key");
        if (crystalProfile < 0 || crystalProfile > 15) throw new IllegalArgumentException("Invalid crystal emission profile");
    }
    public int version() { return backendKey.isEmpty() ? SCALAR_VERSION : EXTENSION_VERSION; }
    public boolean accepts(int savedVersion, String savedBackend, int savedCrystalProfile) {
        return savedVersion == version() && backendKey.equals(savedBackend) && crystalProfile == savedCrystalProfile;
    }
}
