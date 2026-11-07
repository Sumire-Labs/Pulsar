package com.sumirelabs.pulsar.dynamic;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DynamicLightRebuildQueueTest {
    @Test void repeatedMovingUpdatesWaitForTheCurrentMeshAndUseTheLatestState(){
        var queue=new DynamicLightRebuildQueue();var submitted=new ArrayList<Integer>();int[] state={0};
        for(int i=1;i<=20;i++){state[0]=i;queue.add(0,0,0,0,0,0);queue.drain(64,s->true,s->submitted.add(state[0]));}
        assertTrue(submitted.isEmpty());assertFalse(queue.isEmpty());
        queue.drain(64,s->false,s->submitted.add(state[0]));assertEquals(List.of(20),submitted);assertTrue(queue.isEmpty());
        state[0]=0;queue.add(0,0,0,0,0,0);queue.drain(64,s->false,s->submitted.add(state[0]));assertEquals(List.of(20,0),submitted);
    }
    @Test void busySectionsCannotStarveOtherSectionsAndWorldResetDropsPendingWork(){
        var queue=new DynamicLightRebuildQueue();var submitted=new ArrayList<Integer>();queue.add(0,0,0,3,0,0);
        queue.drain(2,s->s.x()<2,s->submitted.add(s.x()));
        queue.drain(2,s->s.x()<2,s->submitted.add(s.x()));assertEquals(List.of(2,3),submitted);
        queue.clear();queue.drain(64,s->false,s->fail("Old-world invalidation survived reset"));
    }
    @Test void failedSubmissionsRemainAvailableForFallback(){
        var queue=new DynamicLightRebuildQueue();queue.add(0,0,0,0,0,0);
        assertThrows(IllegalStateException.class,()->queue.drain(1,s->false,s->{throw new IllegalStateException();}));
        assertFalse(queue.isEmpty());var submitted=new ArrayList<>();queue.drain(1,s->false,submitted::add);assertEquals(1,submitted.size());
    }
}
