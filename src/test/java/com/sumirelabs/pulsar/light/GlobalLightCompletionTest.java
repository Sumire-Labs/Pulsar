package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.util.WorldHeightContext;
import org.junit.jupiter.api.Test;
import java.lang.management.ManagementFactory;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class GlobalLightCompletionTest {
    @Test void crossLaneHandoffCannotBeReportedAsGlobalIdle() throws Exception {
        LightQueue sky=new LightQueue(WorldHeightContext.VANILLA),block=new LightQueue(WorldHeightContext.VANILLA);
        WorldLightManager manager=manager(sky,block);
        block.queueBlockChange(0,64,0);ChunkTasks initial=block.removeFirstTask();
        AtomicReference<Boolean> pending=new AtomicReference<>();CountDownLatch skyQueued=new CountDownLatch(1);
        Thread query=new Thread(()->pending.set(manager.hasUpdates()),"global-idle-query");
        Thread handoff=new Thread(()->{sky.queueBlockChange(0,64,0);skyQueued.countDown();block.completeTask(initial);},"initial-edge-handoff");
        try {
            synchronized(block){
                query.start();awaitBlockedOn(query,block);
                handoff.start();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(skyQueued.getCount()!=0 && !blockedOn(handoff,sky)) {
                    if(System.nanoTime()>deadline)fail("Handoff did not run");
                    Thread.yield();
                }
                // Under the old separate reads, Sky was already observed empty
                // and is now queued; finish Block before allowing its read.
                if(skyQueued.getCount()==0)block.completeTask(initial);
            }
            query.join(5000);handoff.join(5000);
            assertFalse(query.isAlive());assertFalse(handoff.isAlive());
            assertEquals(Boolean.TRUE,pending.get(),"Work existed throughout the cross-lane handoff");
            assertTrue(manager.hasUpdates());
            sky.completeTask(sky.removeFirstTask());assertFalse(manager.hasUpdates());
        } finally { query.interrupt();handoff.interrupt();query.join(5000);handoff.join(5000); }
    }
    @Test void optionalLanesStillReportQueuedAndInFlightWork() throws Exception {
        assertFalse(manager(null,null).hasUpdates());
        for(boolean skyOnly:new boolean[]{false,true}) {
            LightQueue queue=new LightQueue(WorldHeightContext.VANILLA);
            WorldLightManager m=manager(skyOnly?queue:null,skyOnly?null:queue);
            assertFalse(m.hasUpdates());queue.queueBlockChange(0,64,0);assertTrue(m.hasUpdates());
            ChunkTasks task=queue.removeFirstTask();assertTrue(m.hasUpdates());queue.completeTask(task);assertFalse(m.hasUpdates());
        }
    }
    private static boolean blockedOn(Thread thread,Object lock) {
        var info=ManagementFactory.getThreadMXBean().getThreadInfo(thread.threadId());
        return info!=null&&info.getThreadState()==Thread.State.BLOCKED&&info.getLockInfo()!=null
                &&info.getLockInfo().getIdentityHashCode()==System.identityHashCode(lock);
    }
    private static void awaitBlockedOn(Thread thread,Object lock) {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!blockedOn(thread,lock)){if(System.nanoTime()>end)fail("Query did not reach the second lane");Thread.yield();}
    }
    private static WorldLightManager manager(LightQueue sky,LightQueue block)throws Exception {
        Class<?> u=Class.forName("sun.misc.Unsafe");var f=u.getDeclaredField("theUnsafe");f.setAccessible(true);
        var m=(WorldLightManager)u.getMethod("allocateInstance",Class.class).invoke(f.get(null),WorldLightManager.class);
        for(String name:new String[]{"skyQueue","blockQueue"}){var field=WorldLightManager.class.getDeclaredField(name);field.setAccessible(true);field.set(m,name.equals("skyQueue")?sky:block);}
        return m;
    }
}
