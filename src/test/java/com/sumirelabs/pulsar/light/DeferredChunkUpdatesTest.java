package com.sumirelabs.pulsar.light;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class DeferredChunkUpdatesTest {
    @Test void waitingRetainsOwnershipAndRepeatedDeferralsCoalesce() {
        var queue=new DeferredChunkUpdates<Object>(); var chunk=new Object();
        queue.defer(4,chunk); queue.defer(4,chunk);
        queue.drain((key,value)->DeferredChunkUpdates.Decision.WAIT,(key,value)->fail("Sent unfinished light"));
        assertEquals(1,queue.size());
        var sent=new ArrayList<Object>();
        queue.drain((key,value)->DeferredChunkUpdates.Decision.SEND,(key,value)->sent.add(value));
        assertEquals(java.util.List.of(chunk),sent); assertEquals(0,queue.size());
    }
    @Test void retiredChunksNeverSendAndAReplacementOwnsItsRetry() {
        var queue=new DeferredChunkUpdates<Object>(); var old=new Object(); var replacement=new Object();
        queue.defer(1,old); queue.remove(1);
        queue.drain((key,value)->DeferredChunkUpdates.Decision.SEND,(key,value)->fail("Sent retired chunk"));
        queue.defer(1,old); queue.defer(1,replacement);
        queue.drain((key,value)-> { assertSame(replacement,value); return DeferredChunkUpdates.Decision.DROP; },(key,value)->fail("Sent dropped entry"));
        assertEquals(0,queue.size());
    }
    @Test void workResumedDuringSendCanRequeueWithoutBeingLostOrRetriedInSameDrain() {
        var queue=new DeferredChunkUpdates<Object>(); var chunk=new Object(); queue.defer(1,chunk);
        int[] count={0};
        queue.drain((key,value)->DeferredChunkUpdates.Decision.SEND,(key,value)-> { count[0]++; queue.defer(key,value); });
        assertEquals(1,count[0]); assertEquals(1,queue.size()); queue.clear(); assertEquals(0,queue.size());
    }

    @Test void entriesAddedByCallbacksWaitUntilTheNextDrain() {
        var queue = new DeferredChunkUpdates<Object>();
        var original = new Object();
        var added = new Object();
        queue.defer(1, original);
        int[] decisions = {0};

        queue.drain((key, value) -> {
            decisions[0]++;
            queue.defer(2, added);
            return DeferredChunkUpdates.Decision.WAIT;
        }, (key, value) -> fail("Waiting entry must not be sent"));

        assertEquals(1, decisions[0]);
        assertEquals(2, queue.size());
        queue.drain((key, value) -> DeferredChunkUpdates.Decision.SEND, (key, value) -> {});
        assertEquals(0, queue.size());
    }

    @Test void nestedDrainKeepsItsOwnSnapshotAndDoesNotDoubleSend() {
        var queue = new DeferredChunkUpdates<Object>();
        queue.defer(1, new Object());
        queue.defer(2, new Object());
        var sent = new ArrayList<Long>();
        boolean[] nested = {false};

        queue.drain((key, value) -> {
            if (!nested[0]) {
                nested[0] = true;
                queue.drain((nestedKey, nestedValue) -> DeferredChunkUpdates.Decision.SEND,
                        (nestedKey, nestedValue) -> sent.add(nestedKey));
            }
            return DeferredChunkUpdates.Decision.WAIT;
        }, (sendKey, sendValue) -> sent.add(sendKey));

        assertEquals(2, sent.size());
        assertTrue(sent.contains(1L));
        assertTrue(sent.contains(2L));
        assertEquals(0, queue.size());
    }
}
