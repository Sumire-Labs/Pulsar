package com.sumirelabs.pulsar.light;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DeferredChunkWatchesTest {
    @Test void waitingPlayersAreAddedOnceOnlyAfterLightingFinishes() {
        var q = new DeferredChunkWatches<Object,Object,String>();
        Object entry = new Object(), chunk = new Object();var added = new ArrayList<String>();
        q.defer(entry,chunk,"one");q.defer(entry,chunk,"one");q.defer(entry,chunk,"two");
        q.drain((e,c)->true,(e,c)->false,p->true,(e,p)->added.add(p));
        assertTrue(added.isEmpty());
        q.drain((e,c)->true,(e,c)->true,p->true,(e,p)->added.add(p));
        q.drain((e,c)->true,(e,c)->true,p->true,(e,p)->added.add(p));
        assertEquals(List.of("one","two"),added);
    }
    @Test void departureDisconnectAndUnloadedChunksDoNotCreateLateWatchers() {
        var q = new DeferredChunkWatches<Object,Object,String>();
        Object a=new Object(),b=new Object(),chunk=new Object();var added=new ArrayList<String>();
        q.defer(a,chunk,"departed");q.defer(a,chunk,"offline");q.defer(a,chunk,"remaining");
        q.defer(b,chunk,"unloaded");q.cancel(a,"departed");
        q.drain((e,c)->e!=b,(e,c)->true,p->!p.equals("offline"),(e,p)->added.add(p));
        assertEquals(List.of("remaining"),added);
    }
    @Test void replacementChunkDropsItsPreviousPendingPlayers() {
        var q=new DeferredChunkWatches<Object,Object,String>();Object entry=new Object(),old=new Object(),replacement=new Object();var added=new ArrayList<String>();
        q.defer(entry,old,"old");q.defer(entry,replacement,"new");
        q.drain((e,c)->c==replacement,(e,c)->true,p->true,(e,p)->added.add(p));
        assertEquals(List.of("new"),added);
    }
    @Test void reentrantVanillaCallbackCanDeferAnotherAttemptWithoutBeingErased() {
        var q=new DeferredChunkWatches<Object,Object,String>();Object entry=new Object(),chunk=new Object();var added=new ArrayList<String>();
        q.defer(entry,chunk,"first");
        q.drain((e,c)->true,(e,c)->true,p->true,(e,p)->{added.add(p);q.defer(e,chunk,"later");});
        assertEquals(List.of("first"),added);
        q.drain((e,c)->true,(e,c)->true,p->true,(e,p)->added.add(p));
        assertEquals(List.of("first","later"),added);
    }
    @Test void worldShutdownDropsAllDeferredAdds() {
        var q=new DeferredChunkWatches<Object,Object,String>();q.defer(new Object(),new Object(),"one");q.clear();
        q.drain((e,c)->true,(e,c)->true,p->true,(e,p)->fail("Late watcher after shutdown"));
    }
    @Test void aCallbackCanCancelAnotherPlayerInTheSameBatch() {
        var q=new DeferredChunkWatches<Object,Object,String>();Object entry=new Object(),chunk=new Object();var added=new ArrayList<String>();
        q.defer(entry,chunk,"first");q.defer(entry,chunk,"second");
        assertTrue(q.contains(entry,"second"));
        q.drain((e,c)->true,(e,c)->true,p->true,(e,p)->{added.add(p);q.cancel(e,"second");});
        assertEquals(List.of("first"),added);assertTrue(q.isEmpty());
    }
}
