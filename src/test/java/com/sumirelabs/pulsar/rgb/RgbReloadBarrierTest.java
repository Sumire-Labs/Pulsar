package com.sumirelabs.pulsar.rgb;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class RgbReloadBarrierTest {
    @Test void failedOwnerTransactionReleasesTheBarrierForFollowingWork() throws Exception {
        var failed=RgbReloadBarrier.submit(Runnable::run,()->true,()->{throw new IllegalStateException("bad sample");});
        assertThrows(ExecutionException.class,()->failed.get(2,TimeUnit.SECONDS));
        assertEquals(3,RgbReloadBarrier.submit(Runnable::run,()->true,()->3).get(2,TimeUnit.SECONDS));
    }
    @Test void reloadWaitsForWholeTasksAndStopsNewTasksUntilOwnerTransactionFinishes() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        Thread old=new Thread(()->{RgbReloadBarrier.enterLighting();try{entered.countDown();release.await();}
            catch(InterruptedException e){throw new AssertionError(e);}finally{RgbReloadBarrier.exitLighting();}});
        old.start();assertTrue(entered.await(2,TimeUnit.SECONDS));
        var owner=new LinkedBlockingQueue<Runnable>();var fresh=new CountDownLatch(1);
        var future=RgbReloadBarrier.submit(owner::add,()->true,()->7);
        try {
            assertNull(owner.poll(30,TimeUnit.MILLISECONDS));
            release.countDown();old.join(2000);
            Runnable transaction=owner.poll(2,TimeUnit.SECONDS);assertNotNull(transaction);
            Thread next=new Thread(()->{RgbReloadBarrier.enterLighting();try{fresh.countDown();}finally{RgbReloadBarrier.exitLighting();}});
            next.start();assertFalse(fresh.await(30,TimeUnit.MILLISECONDS));
            transaction.run();assertEquals(7,future.get(2,TimeUnit.SECONDS));
            assertTrue(fresh.await(2,TimeUnit.SECONDS));next.join(2000);
        } finally {release.countDown();old.join(2000);}
    }

    @Test void stoppedServerCancelsQueuedOwnerWorkBeforeReleasingTheBarrier() throws Exception {
        var alive=new AtomicBoolean(true);var owner=new LinkedBlockingQueue<Runnable>();var changed=new AtomicBoolean();
        var future=RgbReloadBarrier.submit(owner::add,alive::get,()->{changed.set(true);return 1;});
        Runnable transaction=owner.poll(2,TimeUnit.SECONDS);assertNotNull(transaction);
        alive.set(false);
        assertThrows(ExecutionException.class,()->future.get(2,TimeUnit.SECONDS));
        transaction.run();assertFalse(changed.get());
        var next=RgbReloadBarrier.submit(Runnable::run,()->true,()->2);
        assertEquals(2,next.get(2,TimeUnit.SECONDS));
    }

    @Test void observerCancellationCannotLetQueuedMutationRunOutsideTheBarrier() throws Exception {
        var owner=new LinkedBlockingQueue<Runnable>();var executed=new CountDownLatch(1);var fresh=new CountDownLatch(1);
        var future=RgbReloadBarrier.submit(owner::add,()->true,()->{executed.countDown();return 1;});
        Runnable transaction=owner.poll(2,TimeUnit.SECONDS);assertNotNull(transaction);
        future.cancel(false);
        Thread next=new Thread(()->{RgbReloadBarrier.enterLighting();try{fresh.countDown();}finally{RgbReloadBarrier.exitLighting();}});
        next.start();assertFalse(fresh.await(30,TimeUnit.MILLISECONDS));
        transaction.run();assertTrue(executed.await(2,TimeUnit.SECONDS));
        assertTrue(fresh.await(2,TimeUnit.SECONDS));next.join(2000);
    }
}
