package com.sumirelabs.pulsar.rgb;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BooleanSupplier;

/** One read lease per RGB task, never per cell. Reload waits off the world thread. */
public final class RgbReloadBarrier {
    private static final ReentrantReadWriteLock GATE=new ReentrantReadWriteLock(true);
    private static final AtomicBoolean PENDING=new AtomicBoolean();
    private static final ExecutorService RELOAD=Executors.newSingleThreadExecutor(r->{
        Thread thread=new Thread(r,"Pulsar-PaletteReload");thread.setDaemon(true);return thread;
    });
    private RgbReloadBarrier() {}
    public static boolean pending() { return PENDING.get(); }
    public static void enterLighting() { GATE.readLock().lock(); }
    public static void exitLighting() { GATE.readLock().unlock(); }

    public static <T> CompletableFuture<T> submit(Executor owner,BooleanSupplier alive,Callable<T> transaction) {
        if(!PENDING.compareAndSet(false,true))return CompletableFuture.failedFuture(new IllegalStateException("An RGB palette reload is already pending"));
        var result=new CompletableFuture<T>();
        var completed=new CompletableFuture<T>();
        RELOAD.execute(()->{
            // 0 queued, 1 executing, 2 finished, 3 cancelled before execution.
            AtomicInteger state=new AtomicInteger();
            boolean interrupted=false;
            boolean acquired=false;
            try {
                while(!acquired) {
                    if(!alive.getAsBoolean())throw new CancellationException("Server stopped before palette reload");
                    try {acquired=GATE.writeLock().tryLock(100,TimeUnit.MILLISECONDS);}
                    catch(InterruptedException wakeup){interrupted=true;}
                }
                if(!alive.getAsBoolean())throw new CancellationException("Server stopped before palette reload");
                owner.execute(()->{
                    if(!state.compareAndSet(0,1))return;
                    try {result.complete(transaction.call());}
                    catch(Throwable failure){result.completeExceptionally(failure);}
                    finally {state.set(2);}
                });
                while(!result.isDone()) {
                    if(!alive.getAsBoolean()&&state.compareAndSet(0,3)) {
                        result.completeExceptionally(new CancellationException("Server stopped before palette reload"));
                        break;
                    }
                    try {result.get(100,TimeUnit.MILLISECONDS);}
                    catch(TimeoutException waitingForOwner) { }
                    catch(ExecutionException|CancellationException finished) { break; }
                    catch(InterruptedException wakeup) { interrupted=true; }
                }
            } catch(Throwable failure) { result.completeExceptionally(failure); }
            finally {
                if(acquired)GATE.writeLock().unlock();PENDING.set(false);
                result.whenComplete((value,failure)->{
                    if(failure==null)completed.complete(value);else completed.completeExceptionally(failure);
                });
                if(interrupted)Thread.currentThread().interrupt();
            }
        });
        // Cancelling the caller's notification must not release the gate while
        // an already-scheduled owner transaction can still run.
        return completed.copy();
    }
}
