package com.sumirelabs.pulsar.light;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DeferredLightChecksTest {
    @Test void mergesLanesAndKeepsReentrantRequestsForNextPublication() {
        var checks = new DeferredLightChecks<Object>(); Object chunk = new Object();
        checks.add(chunk, -17, -64, 32, 1);
        checks.add(chunk, -17, -64, 32, 2);
        assertTrue(checks.hasPending());
        var results = new ArrayList<Integer>();
        checks.drain((pos, owner) -> owner == chunk, (x,y,z,lanes) -> {
            assertEquals(new BlockPos(-17,-64,32), new BlockPos(x,y,z));
            results.add(lanes);
            checks.add(chunk,x,y,z,1);
        });
        assertEquals(List.of(3), results);
        checks.drain((pos,owner) -> true, (x,y,z,lanes) -> results.add(lanes));
        assertEquals(List.of(3,1), results);
        assertFalse(checks.hasPending());
    }
    @Test void unloadAndReplacementCannotPublishIntoAnotherChunkOrTruncateHeight() {
        var checks = new DeferredLightChecks<Object>(); Object old = new Object(), current = new Object();
        checks.add(old,0,-64,0,1);
        checks.add(current,0,192,0,2);
        var results = new ArrayList<Integer>();
        checks.drain((pos,owner) -> owner == current, (x,y,z,lanes) -> results.add(y));
        assertEquals(List.of(192),results);
        checks.add(old,-1,400,-1,1);
        checks.removeChunk(-1,-1);
        checks.drain((pos,owner) -> true, (x,y,z,lanes) -> fail("Unloaded work retained"));
    }
}
