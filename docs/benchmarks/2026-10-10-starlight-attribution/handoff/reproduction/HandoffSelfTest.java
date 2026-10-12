package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.util.WorldHeightContext;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class HandoffSelfTest {
    public static void main(String[] args) throws Exception {
        Object owner=new Object(), other=new Object();
        LightQueue sky=new LightQueue(WorldHeightContext.VANILLA),block=new LightQueue(WorldHeightContext.VANILLA),independent=new LightQueue(WorldHeightContext.VANILLA);
        AtomicInteger skyCalls=new AtomicInteger(),blockCalls=new AtomicInteger(),positions=new AtomicInteger();
        AtomicReference<Thread> executed=new AtomicReference<>();
        CountDownLatch otherRan=new CountDownLatch(1), workerRan=new CountDownLatch(1);
        LightEngineWorker first=new LightEngineWorker(sky,()->null,(task,engine)->{skyCalls.incrementAndGet();executed.set(Thread.currentThread());workerRan.countDown();},new AtomicInteger(),"test","unused",false,owner,new AtomicInteger());
        LightEngineWorker second=new LightEngineWorker(block,()->null,(task,engine)->{blockCalls.incrementAndGet();positions.addAndGet(task.changedPositions.size());},new AtomicInteger(),"test","unused",false,owner,new AtomicInteger());
        LightEngineWorker third=new LightEngineWorker(independent,()->null,(task,engine)->otherRan.countDown(),new AtomicInteger(),"test","unused",false,other,new AtomicInteger());
        ParallelLightScheduler pool=ParallelLightScheduler.shared(4);
        pool.register(first);pool.register(second);pool.register(third);pool.pair(first,second);
        try {
            ParallelLightScheduler.diagnosticBegin(owner);
            sky.queueBlockChange(0,64,0);block.queueBlockChange(0,64,0);block.queueBlockChange(0,64,0);
            Future<Void> skyDone=sky.getPendingWorkFuture(0L),blockDone=block.getPendingWorkFuture(0L);
            independent.queueBlockChange(0,64,0);
            check(otherRan.await(5,TimeUnit.SECONDS),"Other world must keep running");
            check(!workerRan.await(100,TimeUnit.MILLISECONDS),"Held owner ran prematurely");
            check(!skyDone.isDone() && !blockDone.isDone(),"Future completed prematurely");
            ParallelLightScheduler.diagnosticDrain(owner);
            check(skyDone.isDone() && blockDone.isDone(),"Caller did not settle futures");
            check(executed.get()==Thread.currentThread(),"Caller path ran on worker");
            check(skyCalls.get()==1 && blockCalls.get()==1 && positions.get()==1,"Duplicate notification did not coalesce");
            ParallelLightScheduler.diagnosticEnd(owner);
            ParallelLightScheduler.diagnosticBegin(owner);
            sky.queueBlockChange(0,64,0);
            Future<Void> done=sky.getPendingWorkFuture(0L);
            check(!done.isDone(),"Gate released early");
            ParallelLightScheduler.diagnosticEnd(owner);
            done.get(5,TimeUnit.SECONDS);
            check(executed.get()!=Thread.currentThread(),"Released path did not run on worker");
            System.out.println("PASS: owner exclusion, independent-world progress, caller execution, merged positions, completion futures, worker resumption");
        } finally {
            first.requestStop();second.requestStop();third.requestStop();first.awaitStop();second.awaitStop();third.awaitStop();
        }
    }
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
}
