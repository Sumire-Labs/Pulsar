package com.sumirelabs.pulsar.light;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;

class TrackedLightSourcesTest {
    @Test void mutableLookupsDoNotReplaceRetainedKeysOrRetainRemovalKeys() {
        var tracked = new TrackedLightSources<net.minecraft.util.math.BlockPos, Object>();
        var key = new net.minecraft.util.math.BlockPos(1, 2, 3);
        Object tile = new Object();
        tracked.update(key, tile);
        var lookup = new net.minecraft.util.math.BlockPos.MutableBlockPos(1, 2, 3);
        assertTrue(tracked.matches(lookup, tile));
        assertFalse(tracked.matches(lookup, new Object()));
        lookup.setPos(4, 5, 6);
        var sampled = new ArrayList<net.minecraft.util.math.BlockPos>();
        tracked.tick((pos, source) -> true, sampled::add);
        assertEquals(java.util.List.of(key), sampled);
        lookup.setPos(1, 2, 3);
        tracked.update(lookup, null);
        lookup.setPos(4, 5, 6);
        assertTrue(tracked.isEmpty());
    }

    @Test void replacementAndRemovalCorrectOldLightAndStopTrackingOldObjects() {
        var tracked = new TrackedLightSources<Integer, Object>();
        var live = new HashMap<Integer, Object>();
        Object old = new Object(), replacement = new Object();
        tracked.update(4, old);
        live.put(4, old);
        var sampled = new ArrayList<Integer>();
        tracked.tick((key, tile) -> live.get(key) == tile, sampled::add);
        assertEquals(java.util.List.of(4), sampled);
        live.put(4, replacement);
        sampled.clear();
        tracked.tick((key, tile) -> live.get(key) == tile, sampled::add);
        assertEquals(java.util.List.of(4), sampled);
        assertTrue(tracked.isEmpty());
        tracked.update(4, replacement);
        live.clear();
        tracked.tick((key, tile) -> live.get(key) == tile, sampled::add);
        assertTrue(tracked.isEmpty());
    }
}
