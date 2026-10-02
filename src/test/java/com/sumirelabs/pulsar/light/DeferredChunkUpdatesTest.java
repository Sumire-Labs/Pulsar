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
}
