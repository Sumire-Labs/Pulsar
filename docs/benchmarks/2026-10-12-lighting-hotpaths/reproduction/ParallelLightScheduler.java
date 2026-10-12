package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.Pulsar;
import java.util.ArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded process-wide workers with ScalableLux-style world/5x5 exclusion. */
final class ParallelLightScheduler {
    private static ParallelLightScheduler shared;

    static synchronized ParallelLightScheduler shared(int threads) {
        if (shared == null) shared = new ParallelLightScheduler(Math.max(1, Math.min(16, threads)));
        return shared;
    }

    private record Claim(LightEngineWorker lane, ChunkTasks task, LightEngineWorker partner) {}
    private final ArrayList<LightEngineWorker> lanes = new ArrayList<>();
    private final ChunkTaskReservations reservations = new ChunkTaskReservations();
    private final Semaphore work = new Semaphore(0);
    private final AtomicBoolean signalled = new AtomicBoolean();
    private int turn;
    private static final long IDLE_SPIN_NS = Math.max(0L, Math.min(50000L, Long.getLong("pulsar.diag.idleSpinNs", 5000L)));
    private Object diagnosticOwner;
    private Thread diagnosticThread;

    static void diagnosticBegin(Object owner) throws InterruptedException {
        ParallelLightScheduler s=shared;
        long deadline=System.nanoTime()+5_000_000_000L;
        synchronized(s) {
            if(s.diagnosticOwner!=null) throw new IllegalStateException("Nested diagnostic owner");
            s.diagnosticOwner=owner;s.diagnosticThread=Thread.currentThread();
        }
        while(true) {
            synchronized(s) { if(s.reservations.activeJobs(owner)==0) return; }
            if(Thread.currentThread().isInterrupted() || System.nanoTime()>deadline) {
                synchronized(s) { s.diagnosticOwner=null;s.diagnosticThread=null; }
                s.signal();
                if(Thread.currentThread().isInterrupted()) throw new InterruptedException("Diagnostic hold interrupted");
                throw new IllegalStateException("Diagnostic reservation timeout");
            }
            Thread.onSpinWait();
        }
    }

    static void diagnosticDrain(Object owner) {
        ParallelLightScheduler s=shared;
        // World preparation also invokes the barrier outside timed edits.
        if(s.diagnosticOwner==null) return;
        if(s.diagnosticOwner!=owner || s.diagnosticThread!=Thread.currentThread()) throw new IllegalStateException("Wrong diagnostic caller");
        Claim c;
        while((c=s.claim())!=null) s.execute(c);
    }

    static void diagnosticEnd(Object owner) {
        ParallelLightScheduler s=shared;
        synchronized(s) {
            if(s.diagnosticOwner!=owner || s.diagnosticThread!=Thread.currentThread() || s.reservations.activeJobs(owner)!=0) throw new IllegalStateException("Diagnostic release mismatch");
            s.diagnosticOwner=null;s.diagnosticThread=null;
        }
        s.signal();
    }

    private ParallelLightScheduler(int threads) {
        for (int i = 0; i < threads; i++) {
            Thread thread = new Thread(this::run, "Pulsar-Parallel-" + i);
            thread.setDaemon(true);
            thread.start();
        }
    }

    synchronized void register(LightEngineWorker lane) {
        lanes.add(lane);
        lane.setParallelWakeup(this::signal);
        signal();
    }

    synchronized void pair(LightEngineWorker first, LightEngineWorker second) {
        if (first.lockOwner() != second.lockOwner()) throw new IllegalArgumentException("Different worlds");
        first.partner = second;
        second.partner = first;
    }

    synchronized void unregister(LightEngineWorker lane) {
        lanes.remove(lane);
        lane.setParallelWakeup(null);
        signal();
    }

    // Queue insertion may call this under its monitor; never take our lock here.
    void signal() {
        if (signalled.compareAndSet(false, true)) work.release();
    }

    private synchronized Claim claim() {
        for (int tried = 0; tried < lanes.size(); tried++) {
            turn %= lanes.size();
            LightEngineWorker lane = lanes.get(turn++);
            Object owner = lane.lockOwner();
            if(Thread.currentThread()==diagnosticThread ? owner!=diagnosticOwner : owner==diagnosticOwner) continue;
            ChunkTasks task = lane.claimAvailable(key -> reservations.available(owner, key));
            if (task == null) continue;
            reservations.reserve(owner, task.chunkCoordinate);
            lane.jobClaimed(reservations.activeJobs(owner));
            LightEngineWorker partner = lane.partner;
            return new Claim(lane, task, partner);
        }
        return null;
    }

    /** Keep the shared footprint reserved, but let producer updates merge until this lane starts. */
    private synchronized ChunkTasks claimPartner(final Claim claim) {
        final ChunkTasks task = claim.partner == null ? null : claim.partner.claimChunk(claim.task.chunkCoordinate);
        if (task != null) claim.partner.jobClaimed(reservations.activeJobs(claim.lane.lockOwner()));
        return task;
    }

    private void run() {
        long warmUntil = 0L;
        while (true) {
            Claim claim = claim();
            if (claim == null) {
                if (IDLE_SPIN_NS > 0L) {
                    // A consumed signal must be followed by a fresh claim, not a wait.
                    if (work.tryAcquire()) {
                        signalled.set(false);
                        continue;
                    }
                    if (warmUntil != 0L && System.nanoTime() - warmUntil < 0L) {
                        while (!signalled.get() && System.nanoTime() - warmUntil < 0L) {
                            Thread.onSpinWait();
                        }
                        if (signalled.get()) continue;
                    }
                }
                try { work.acquire(); } catch (InterruptedException stopped) { return; }
                signalled.set(false);
                continue;
            }
            signal(); // Wake another worker to claim an independent footprint.
            execute(claim);
            if (IDLE_SPIN_NS > 0L) warmUntil = System.nanoTime() + IDLE_SPIN_NS;
        }
    }

    private void execute(Claim claim) {
            ChunkTasks partnerTask = null;
            try {
                process(claim.lane, claim.task);
                if (claim.partner != null) partnerTask = this.claimPartner(claim);
                if (partnerTask != null) process(claim.partner, partnerTask);
            } finally {
                synchronized (this) {
                    reservations.release(claim.lane.lockOwner(), claim.task.chunkCoordinate);
                }
                claim.lane.jobFinished();
                if (partnerTask != null) claim.partner.jobFinished();
                signal();
            }
    }

    private static void process(LightEngineWorker lane, ChunkTasks task) {
        try { lane.processClaimedTask(task); }
        catch (Throwable error) { Pulsar.LOGGER.error("Parallel lighting job failed", error); }
    }
}
