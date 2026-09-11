package com.sumirelabs.pulsar.light;

import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/** Server-thread retry ownership; a chunk replacement must not inherit an old send. */
final class DeferredChunkUpdates<T> {
    enum Decision { WAIT, SEND, DROP }
    private final Map<Long,T> pending = new HashMap<>();
    void defer(long key,T chunk) { pending.put(key,chunk); }
    void remove(long key) { pending.remove(key); }
    void clear() { pending.clear(); }
    int size() { return pending.size(); }
    void drain(BiFunction<Long,T,Decision> decision,BiConsumer<Long,T> send) {
        if(pending.isEmpty()) return;
        // Sending may defer the same entry again if a worker became busy.
        var snapshot=new ArrayList<Map.Entry<Long,T>>(pending.size());
        pending.forEach((key,value)->snapshot.add(Map.entry(key,value)));
        for(var entry:snapshot) {
            long key=entry.getKey(); T chunk=entry.getValue();
            if(pending.get(key)!=chunk) continue;
            var action=decision.apply(key,chunk);
            if(action==Decision.WAIT) continue;
            pending.remove(key);
            if(action==Decision.SEND) send.accept(key,chunk);
        }
    }
}
