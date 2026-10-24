package com.sumirelabs.pulsar.light;

import java.util.function.BiPredicate;
import java.util.function.Predicate;

/** Initial sends get another readiness opportunity before the next tick adds work. */
final class InitialChunkSendRetries<E,C> {
    private record Request<E,C>(E entry,C chunk) {}
    private final DeferredChunkUpdates<Request<E,C>> pending=new DeferredChunkUpdates<>();
    int size(){return pending.size();}
    void remove(long key){pending.remove(key);}
    void clear(){pending.clear();}
    void defer(long key,E entry,C chunk){
        var old=pending.get(key);
        if(old==null||old.entry()!=entry||old.chunk()!=chunk)pending.defer(key,new Request<>(entry,chunk));
    }
    void drain(BiPredicate<E,C> current,BiPredicate<E,C> ready,Predicate<E> send){drain(81,current,ready,send);}
    void drain(int limit,BiPredicate<E,C> current,BiPredicate<E,C> ready,Predicate<E> send){
        int[] remaining={Math.max(0,limit)};
        pending.drain((key,request)->!current.test(request.entry(),request.chunk())?DeferredChunkUpdates.Decision.DROP
                :remaining[0]>0&&ready.test(request.entry(),request.chunk())?DeferredChunkUpdates.Decision.SEND:DeferredChunkUpdates.Decision.WAIT,
                (key,request)->{
                    --remaining[0];
                    if(!send.test(request.entry())&&pending.get(key)==null&&current.test(request.entry(),request.chunk()))
                        defer(key,request.entry(),request.chunk());
                });
    }
}
