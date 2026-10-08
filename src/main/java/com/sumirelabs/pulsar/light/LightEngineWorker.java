package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.Pulsar;
import com.sumirelabs.pulsar.config.PulsarConfig;
import com.sumirelabs.pulsar.light.engine.PulsarEngine;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.function.LongPredicate;

/**
 * Drains one light lane and owns that lane's thread and reusable engine pool.
 *
 * <p>The server uses dedicated daemon lanes or the optional shared dispatcher.
 * The thin client
 * invokes {@link #processPending()} from its main-thread tick instead.
 */
final class LightEngineWorker {

    private static final int MAX_CACHED_ENGINES = 4;
    private static final long SERVER_BATCH_BUDGET_NS = 15_000_000L;

    private final LightQueue queue;
    private final LightTaskScheduler scheduler;
    private final ConcurrentLinkedDeque<PulsarEngine> enginePool = new ConcurrentLinkedDeque<>();
    private final Supplier<PulsarEngine> engineFactory;
    private final BiConsumer<ChunkTasks, PulsarEngine> taskProcessor;
    private final AtomicInteger budgetYields;
    private final String operationName;
    private final Thread thread;
    private final ParallelLightScheduler parallel;
    private final Object lockOwner;
    private final AtomicInteger parallelJobsMax;
    private final Object completionMonitor = new Object();
    private int activeJobs;

    private volatile boolean running = true;

    LightEngineWorker(final LightQueue queue,
                      final Supplier<PulsarEngine> engineFactory,
                      final BiConsumer<ChunkTasks, PulsarEngine> taskProcessor,
                      final AtomicInteger budgetYields,
                      final String operationName,
                      final String threadName,
                      final boolean startThread,
                      final Object lockOwner,
                      final AtomicInteger parallelJobsMax) {
        this.queue = queue;
        this.scheduler = new LightTaskScheduler(queue, System::nanoTime);
        this.engineFactory = engineFactory;
        this.taskProcessor = taskProcessor;
        this.budgetYields = budgetYields;
        this.operationName = operationName;
        this.lockOwner = lockOwner;
        this.parallelJobsMax = parallelJobsMax;

        if (startThread && PulsarConfig.features.experimentalServerLightThreads > 0) {
            this.thread = null;
            this.parallel = ParallelLightScheduler.shared(PulsarConfig.features.experimentalServerLightThreads);
            this.parallel.register(this);
        } else if (startThread) {
            this.parallel = null;
            this.thread = new Thread(this::run, threadName);
            this.thread.setDaemon(true);
            this.thread.start();
        } else {
            this.parallel = null;
            this.thread = null;
        }
    }

    private void run() {
        while (this.running) {
            if (this.queue.isEmpty()) {
                try {
                    this.queue.waitForWork();
                } catch (final InterruptedException e) {
                    break;
                }
            }
            if (this.running) {
                this.queue.clearWorkSignal();
                this.processPending();
            }
        }
    }

    void processPending() {
        this.processPendingUntil(System.nanoTime() + SERVER_BATCH_BUDGET_NS);
    }

    Object lockOwner() { return this.lockOwner; }
    void setParallelWakeup(Runnable wakeup) { this.queue.setParallelWakeup(wakeup); }

    ChunkTasks claimAvailable(LongPredicate allowed) {
        return this.running ? this.scheduler.claimAvailable(allowed) : null;
    }

    void jobClaimed(int simultaneousWorldJobs) {
        synchronized (this.completionMonitor) { this.activeJobs++; }
        if (LightStats.enabled) this.parallelJobsMax.accumulateAndGet(simultaneousWorldJobs, Math::max);
    }

    void jobFinished() {
        synchronized (this.completionMonitor) {
            this.activeJobs--;
            this.completionMonitor.notifyAll();
        }
    }

    void processClaimedTask(ChunkTasks task) {
        PulsarEngine engine = null;
        try {
            engine = this.acquireEngine();
            this.taskProcessor.accept(task, engine);
        } finally {
            if (engine != null) this.releaseEngine(engine);
            this.queue.completeTask(task);
        }
    }

    /** Client lanes take turns after each complete task, sharing one deadline. */
    boolean processOnePendingUntil(final long deadline) {
        if (!this.running || this.queue.isEmpty() || System.nanoTime() - deadline >= 0L) return false;
        final PulsarEngine engine = this.acquireEngine();
        try {
            return this.scheduler.drainOneUntil(deadline, () -> this.running,
                    task -> this.taskProcessor.accept(task, engine));
        } catch (final Throwable t) {
            Pulsar.LOGGER.error("Exception in " + this.operationName, t);
            return false;
        } finally {
            this.releaseEngine(engine);
        }
    }

    void processPendingUntil(final long deadline) {
        if (!this.running || this.queue.isEmpty()) {
            return;
        }
        if (System.nanoTime() - deadline >= 0L) return;
        final PulsarEngine engine = this.acquireEngine();
        try {
            if (this.scheduler.drainUntil(deadline, () -> this.running,
                    task -> this.taskProcessor.accept(task, engine)) && LightStats.enabled) {
                this.budgetYields.incrementAndGet();
            }
        } catch (final Throwable t) {
            Pulsar.LOGGER.error("Exception in " + this.operationName, t);
        } finally {
            this.releaseEngine(engine);
        }
    }

    private PulsarEngine acquireEngine() {
        final PulsarEngine cached = this.enginePool.pollFirst();
        return cached != null ? cached : this.engineFactory.get();
    }

    private void releaseEngine(final PulsarEngine engine) {
        if (this.enginePool.size() < MAX_CACHED_ENGINES) {
            this.enginePool.addFirst(engine);
        }
    }

    void requestStop() {
        this.running = false;
        if (this.parallel != null) this.parallel.unregister(this);
        this.queue.wakeUp();
    }

    void awaitStop() {
        if (this.thread == null) {
            final long deadline = System.nanoTime() + 1_000_000_000L;
            synchronized (this.completionMonitor) {
                while (this.activeJobs != 0) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0L) break;
                    try { this.completionMonitor.wait(Math.max(1L, remaining / 1_000_000L)); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                }
            }
            return;
        }
        try {
            this.thread.join(1000L);
        } catch (final InterruptedException ignored) {
        }
    }
}
