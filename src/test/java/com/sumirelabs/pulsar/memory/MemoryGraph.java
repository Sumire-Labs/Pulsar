package com.sumirelabs.pulsar.memory;

import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;

/** Reachable bytes counted once per graph, excluding external world/chunk/state/thread roots.
 * This is not an exclusive retained-size/dominator analysis of a full heap dump. */
public final class MemoryGraph {
    private MemoryGraph(){}
    public static long bytes(Object root) {
        var seen=new IdentityHashMap<Object,Boolean>();var pending=new ArrayDeque<Object>();pending.add(root);long bytes=0;
        while(!pending.isEmpty()) {
            Object object=pending.removeLast();
            if(seen.put(object,Boolean.TRUE)!=null || object instanceof Thread || object instanceof Class<?>
                    || object instanceof Enum<?> || object instanceof net.minecraft.world.World
                    || object instanceof net.minecraft.world.chunk.Chunk
                    || object instanceof net.minecraft.block.state.IBlockState)continue;
            bytes+=MemoryAgent.size(object);
            if(object instanceof Object[] array){for(Object value:array)if(value!=null)pending.add(value);continue;}
            if(object.getClass().isArray())continue;
            for(Class<?> type=object.getClass();type!=null;type=type.getSuperclass())for(var field:type.getDeclaredFields()) {
                if(Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive())continue;
                try {
                    if(!field.trySetAccessible())throw new IllegalStateException("Unmeasured field: "+field);
                    Object value=field.get(object);if(value!=null)pending.add(value);
                }catch(IllegalAccessException error){throw new AssertionError(error);}
            }
        }
        return bytes;
    }
}
