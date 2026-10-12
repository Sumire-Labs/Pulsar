package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.util.WorldHeightContext;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ParallelLightSchedulerWakeRaceTest {
    @Test
    void repeatedEmptyToNonemptyTransitionsCannotLoseNotificationsOrCompletionFutures() throws Exception {
        LightQueue queue=new LightQueue(WorldHeightContext.VANILLA);
        AtomicInteger processed=new AtomicInteger();
        LightEngineWorker lane=new LightEngineWorker(queue,()->null,
                (task,engine)->processed.addAndGet(task.changedPositions.size()),
                new AtomicInteger(),"test","unused",false,new Object(),new AtomicInteger());
        ParallelLightScheduler pool=ParallelLightScheduler.shared(4);
        pool.register(lane);
        try {
            for(int i=0;i<2000;i++) {
                queue.queueBlockChange(0,64,0);
                var future=queue.getPendingWorkFuture(0L);
                if(future!=null)future.get(5,TimeUnit.SECONDS);
                assertEquals(i+1,processed.get(),"Every update must run, including an already-completed future");
            }
            assertFalse(queue.hasWork());
        } finally {
            pool.unregister(lane);lane.requestStop();lane.awaitStop();
        }
    }
}
