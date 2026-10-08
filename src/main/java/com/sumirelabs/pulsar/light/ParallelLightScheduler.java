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

    private record Claim(LightEngineWorker lane, ChunkTasks task,
                         LightEngineWorker partner, ChunkTasks partnerTask) {}
    private final ArrayList<LightEngineWorker> lanes = new ArrayList<>();
    private final ChunkTaskReservations reservations = new ChunkTaskReservations();
    private final Semaphore work = new Semaphore(0);
    private final AtomicBoolean signalled = new AtomicBoolean();
    private int turn;

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
            ChunkTasks task = lane.claimAvailable(key -> reservations.available(owner, key));
            if (task == null) continue;
            reservations.reserve(owner, task.chunkCoordinate);
            lane.jobClaimed(reservations.activeJobs(owner));
            LightEngineWorker partner = lane.partner;
            ChunkTasks partnerTask = partner == null ? null : partner.claimChunk(task.chunkCoordinate);
            if (partnerTask != null) partner.jobClaimed(reservations.activeJobs(owner));
            return new Claim(lane, task, partner, partnerTask);
        }
        return null;
    }

    private void run() {
        while (true) {
            Claim claim = claim();
            if (claim == null) {
                try { work.acquire(); } catch (InterruptedException stopped) { return; }
                signalled.set(false);
                continue;
            }
            signal(); // Wake another worker to claim an independent footprint.
            try {
                process(claim.lane, claim.task);
                if (claim.partnerTask != null) process(claim.partner, claim.partnerTask);
            } finally {
                synchronized (this) {
                    reservations.release(claim.lane.lockOwner(), claim.task.chunkCoordinate);
                }
                claim.lane.jobFinished();
                if (claim.partnerTask != null) claim.partner.jobFinished();
                signal();
            }
        }
    }

    private static void process(LightEngineWorker lane, ChunkTasks task) {
        try { lane.processClaimedTask(task); }
        catch (Throwable error) { Pulsar.LOGGER.error("Parallel lighting job failed", error); }
    }
}
