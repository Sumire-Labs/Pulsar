package com.sumirelabs.pulsar.light;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BulkSkyRelightPolicyTest {

    @Test
    void leavesOrdinaryEditsOnIncrementalPath() {
        assertFalse(BulkSkyRelightPolicy.shouldPromote(false, 1));
        assertFalse(BulkSkyRelightPolicy.shouldPromote(false, 15));
        assertTrue(BulkSkyRelightPolicy.shouldPromote(false, 16));
        // Initial propagation and generation-tagged final-edge tasks must
        // keep their completion bookkeeping; both pass coordinatedTask=true.
        assertFalse(BulkSkyRelightPolicy.shouldPromote(true, 256));
    }
}
