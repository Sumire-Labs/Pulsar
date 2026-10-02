package com.sumirelabs.pulsar.light;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LightCacheIdentityTest {
    @Test void ordinaryCachesRemainCompatibleWithoutAnAddon() {
        var scalar = new LightCacheIdentity("", 10);
        assertEquals(10, scalar.version());
        assertTrue(scalar.accepts(10, "", 10));
        assertFalse(scalar.accepts(9, "", 10));
    }
    @Test void switchingBackendSettingsOrRemovingAddonRejectsOldCache() {
        var a = new LightCacheIdentity("pcla:rgb-v1/aaa", 10);
        assertEquals(11, a.version());
        assertTrue(a.accepts(11, "pcla:rgb-v1/aaa", 10));
        assertFalse(a.accepts(11, "pcla:rgb-v1/bbb", 10));
        assertFalse(a.accepts(11, "pcla:rgb-v1/aaa", 0));
        assertFalse(a.accepts(10, "", 10));
        assertFalse(new LightCacheIdentity("", 10).accepts(11, a.backendKey(), 10));
    }
    @Test void malformedKeysAreRejectedBeforeTheyReachNbt() {
        assertThrows(IllegalArgumentException.class, () -> new LightCacheIdentity(null, 0));
        assertThrows(IllegalArgumentException.class, () -> new LightCacheIdentity("bad key", 0));
        assertThrows(IllegalArgumentException.class, () -> new LightCacheIdentity("x".repeat(257), 0));
    }
}
