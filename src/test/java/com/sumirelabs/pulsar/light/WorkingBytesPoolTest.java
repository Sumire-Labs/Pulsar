package com.sumirelabs.pulsar.light;

import java.lang.ref.Reference;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkingBytesPoolTest {
    @Test void overflowReclamationLeavesHotBuffersReusableAndNeverReturnsDuplicateLeases() throws Exception {
        var pool=new WorkingBytesPool(256);
        var buffers=new byte[256][];
        for(int i=0;i<buffers.length;i++){buffers[i]=new byte[2048];pool.addFirst(buffers[i]);}
        var field=WorkingBytesPool.class.getDeclaredField("cold");field.setAccessible(true);
        // Deterministically simulate the JVM clearing the weak overflow at GC.
        ((Reference<?>)field.get(pool)).clear();
        assertEquals(WorkingBytesPool.HOT_LIMIT,pool.size());
        var seen=new HashSet<byte[]>();
        byte[] buffer;
        while((buffer=pool.pollFirst())!=null)assertTrue(seen.add(buffer));
        assertEquals(WorkingBytesPool.HOT_LIMIT,seen.size());
        for(byte[] original:buffers)pool.addFirst(original);
        seen.clear();
        while((buffer=pool.pollFirst())!=null)assertTrue(seen.add(buffer));
        assertTrue(seen.size()>=WorkingBytesPool.HOT_LIMIT);
        assertTrue(seen.size()<=256);
    }

    @Test void retainedCapacityIsBoundedAndCheckedOutBuffersAreNotReused() {
        var pool=new WorkingBytesPool(2);
        byte[] a=new byte[2048],b=new byte[2048],c=new byte[2048];
        pool.addFirst(a);pool.addFirst(b);pool.addFirst(c);
        assertEquals(2,pool.size());
        assertSame(b,pool.pollFirst());assertSame(a,pool.pollFirst());assertNull(pool.pollFirst());
        pool.addFirst(a);assertSame(a,pool.pollFirst());assertNull(pool.pollFirst());
    }
}
