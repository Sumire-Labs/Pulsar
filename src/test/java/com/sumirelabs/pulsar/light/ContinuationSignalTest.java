package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.util.WorldHeightContext;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContinuationSignalTest {
    @Test void requestsCoalesceAndRequestsDuringActionSurviveForNextTurn() {
        var wakes=new AtomicInteger();var signal=new ContinuationSignal(wakes::incrementAndGet);var calls=new AtomicInteger();
        for(int i=0;i<1000;i++)signal.request();assertEquals(1,wakes.get());
        signal.runOne(()->{calls.incrementAndGet();signal.request();signal.request();});
        assertTrue(signal.pending());assertEquals(2,wakes.get());
        signal.runOne(calls::incrementAndGet);signal.runOne(calls::incrementAndGet);
        assertEquals(2,calls.get());assertFalse(signal.pending());
    }
    @Test void callbackFailureDoesNotEraseANewerRequestAndCloseCannotBeReopened() {
        var signal=new ContinuationSignal(()->{});signal.request();
        assertThrows(IllegalStateException.class,()->signal.runOne(()->{signal.request();throw new IllegalStateException();}));
        assertTrue(signal.pending());signal.close();signal.request();assertFalse(signal.pending());
        signal.runOne(()->fail("Closed signal executed"));
    }
    @Test void realWorkerResumesWithoutScalarTasksAndNeverAllocatesAScalarEngine() throws Exception {
        var queue=new LightQueue(WorldHeightContext.VANILLA);var calls=new AtomicInteger();var done=new CountDownLatch(1);
        var workers=new LightEngineWorker[1];
        workers[0]=new LightEngineWorker(queue,()->{throw new AssertionError("Scalar engine allocated for continuation");},
                (task,engine)->fail("Scalar task invented"),new AtomicInteger(),new AtomicInteger(),"test","continuation-test",true,()->{
                    if(calls.incrementAndGet()==1)workers[0].requestContinuation();else done.countDown();
                });
        try {
            workers[0].requestContinuation();assertTrue(done.await(5,TimeUnit.SECONDS));assertEquals(2,calls.get());assertTrue(queue.isEmpty());
        } finally {workers[0].requestStop();workers[0].awaitStop();}
        workers[0].requestContinuation();workers[0].processPending();assertEquals(2,calls.get());
    }
    @Test void requestBetweenEmptyCheckAndWaitIsNotLost() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var done=new CountDownLatch(1);
        var queue=new LightQueue(WorldHeightContext.VANILLA);var signal=new ContinuationSignal(queue::wakeUp);
        var thread=new Thread(()->{
            try {
                if(queue.isEmpty() && !signal.pending()){entered.countDown();release.await();queue.waitForWork();}
                signal.runOne(done::countDown);
            } catch(InterruptedException e){Thread.currentThread().interrupt();}
        });
        thread.start();
        try {assertTrue(entered.await(5,TimeUnit.SECONDS));signal.request();release.countDown();assertTrue(done.await(5,TimeUnit.SECONDS));}
        finally {release.countDown();thread.interrupt();thread.join(1000);}
    }
    @Test void repeatedSelfContinuationsDoNotAccumulateWakePermits() throws Exception {
        var queue=new LightQueue(WorldHeightContext.VANILLA);var calls=new AtomicInteger();var done=new CountDownLatch(1);var workers=new LightEngineWorker[1];
        workers[0]=new LightEngineWorker(queue,()->{throw new AssertionError();},(task,engine)->fail(),new AtomicInteger(),new AtomicInteger(),"test","repeated-continuation",true,()->{
            if(calls.incrementAndGet()<10000)workers[0].requestContinuation();else done.countDown();
        });
        try {workers[0].requestContinuation();assertTrue(done.await(5,TimeUnit.SECONDS));}
        finally {workers[0].requestStop();workers[0].awaitStop();}
        assertEquals(10000,calls.get());assertTrue(queue.clearWorkSignal()<=2);
    }
    @Test void thinClientProcessesOnlyOneContinuationPerDrain() {
        var queue=new LightQueue(WorldHeightContext.VANILLA);var calls=new AtomicInteger();var workers=new LightEngineWorker[1];
        workers[0]=new LightEngineWorker(queue,()->{throw new AssertionError();},(task,engine)->fail(),
                new AtomicInteger(),new AtomicInteger(),"test","unused",false,()->{calls.incrementAndGet();workers[0].requestContinuation();});
        workers[0].requestContinuation();workers[0].processPending();assertEquals(1,calls.get());
        workers[0].processPending();assertEquals(2,calls.get());workers[0].requestStop();workers[0].processPending();assertEquals(2,calls.get());
    }
}
