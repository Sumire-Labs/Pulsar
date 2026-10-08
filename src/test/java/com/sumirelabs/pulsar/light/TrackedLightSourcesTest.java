package com.sumirelabs.pulsar.light;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;

class TrackedLightSourcesTest {
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
