package com.sumirelabs.pulsar.light.nativeprobe;

import com.sumirelabs.pulsar.light.SWMRNibbleArray;
import com.sumirelabs.pulsar.util.CoordinateUtils;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class RegionWriteAudit {
    private static final ThreadLocal<Long> CENTER = new ThreadLocal<>();
    private static final Map<SWMRNibbleArray,Long> OWNERS = Collections.synchronizedMap(new WeakHashMap<>());
    public static final AtomicLong writes = new AtomicLong();
    public static void enter(int x,int z) { CENTER.set(CoordinateUtils.getChunkKey(x,z)); }
    public static void leave() { CENTER.remove(); }
    public static void tag(SWMRNibbleArray nibble,int x,int z) {
        check(x,z);
        if(nibble!=null)OWNERS.put(nibble,CoordinateUtils.getChunkKey(x,z));
    }
    public static void writing(SWMRNibbleArray nibble) {
        Long owner=OWNERS.get(nibble);
        if(owner!=null)check(CoordinateUtils.getChunkX(owner),CoordinateUtils.getChunkZ(owner));
    }
    public static void check(int x,int z) {
        Long center=CENTER.get();if(center==null)return;
        if(Math.abs(x-CoordinateUtils.getChunkX(center))>1 || Math.abs(z-CoordinateUtils.getChunkZ(center))>1)
            throw new AssertionError("Write escaped 3x3 footprint: center="+center+" chunk="+x+","+z);
        writes.incrementAndGet();
    }
}
