package com.sumirelabs.pulsar.light;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
class InitialChunkSendRetriesTest {
    @Test void repeatedDeferralCoalescesAndReadinessWaits(){
        var q=new InitialChunkSendRetries<Object,Object>();Object e=new Object(),c=new Object();for(int i=0;i<20;i++)q.defer(1,e,c);
        assertEquals(1,q.size());AtomicBoolean ready=new AtomicBoolean();List<Object> sent=new ArrayList<>();
        q.drain((a,b)->true,(a,b)->ready.get(),a->{sent.add(a);return true;});assertTrue(sent.isEmpty());assertEquals(1,q.size());
        ready.set(true);q.drain((a,b)->true,(a,b)->ready.get(),a->{sent.add(a);return true;});assertEquals(List.of(e),sent);assertEquals(0,q.size());
    }
    @Test void invalidEntryOrChunkIsDroppedWithoutSending(){
        var q=new InitialChunkSendRetries<Object,Object>();Object old=new Object(),replacement=new Object();q.defer(1,old,new Object());
        q.drain((e,c)->e==replacement,(e,c)->true,e->{fail("stale send");return true;});assertEquals(0,q.size());
    }
    @Test void failedSendGetsAnotherOpportunity(){
        var q=new InitialChunkSendRetries<Object,Object>();q.defer(1,new Object(),new Object());AtomicInteger calls=new AtomicInteger();
        q.drain((e,c)->true,(e,c)->true,e->calls.incrementAndGet()>1);assertEquals(1,q.size());
        q.drain((e,c)->true,(e,c)->true,e->calls.incrementAndGet()>1);assertEquals(0,q.size());assertEquals(2,calls.get());
    }
    @Test void callbackReplacementIsNotOverwrittenByFailedOldSend(){
        var q=new InitialChunkSendRetries<Object,Object>();Object old=new Object(),replacement=new Object(),chunk=new Object();q.defer(1,old,chunk);
        q.drain((e,c)->true,(e,c)->true,e->{q.defer(1,replacement,chunk);return false;});List<Object> sent=new ArrayList<>();
        q.drain((e,c)->true,(e,c)->true,e->{sent.add(e);return true;});assertEquals(List.of(replacement),sent);
    }
    @Test void invalidationDuringSendDoesNotResurrectRequest(){
        var q=new InitialChunkSendRetries<Object,Object>();AtomicBoolean live=new AtomicBoolean(true);q.defer(1,new Object(),new Object());
        q.drain((e,c)->live.get(),(e,c)->true,e->{live.set(false);q.clear();return false;});assertEquals(0,q.size());
    }
    @Test void removeAndClearCancelRequests(){
        var q=new InitialChunkSendRetries<Object,Object>();q.defer(1,new Object(),new Object());q.defer(2,new Object(),new Object());q.remove(1);assertEquals(1,q.size());q.clear();assertEquals(0,q.size());
    }
    @Test void retryBudgetLimitsSendsAndRetainsRemainingRequests(){
        var q=new InitialChunkSendRetries<Object,Object>();for(int i=0;i<100;i++)q.defer(i,new Object(),new Object());AtomicInteger sent=new AtomicInteger();
        q.drain((e,c)->true,(e,c)->true,e->{sent.incrementAndGet();return true;});assertEquals(81,sent.get());assertEquals(19,q.size());
        q.drain((e,c)->true,(e,c)->true,e->{sent.incrementAndGet();return true;});assertEquals(100,sent.get());assertEquals(0,q.size());
    }
}
