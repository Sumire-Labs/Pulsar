package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.Pulsar;
import com.sumirelabs.pulsar.light.engine.PulsarEngine;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Drains one light lane and owns that lane's thread and reusable engine pool.
 *
 * <p>The server runs one instance per lane on a daemon thread. The thin client
 * invokes {@link #processPending()} from its main-thread tick instead.
 */
final class LightEngineWorker {

    private static final int MAX_CACHED_ENGINES = 4;
    private static final long EDGE_CHECK_BUDGET_NS = 10_000_000L;
    private static final long BLOCK_CHANGE_BUDGET_NS = 5_000_000L;

    private final LightQueue queue;
    private final ConcurrentLinkedDeque<PulsarEngine> enginePool = new ConcurrentLinkedDeque<>();
    private final Supplier<PulsarEngine> engineFactory;
    private final BiConsumer<ChunkTasks, PulsarEngine> taskProcessor;
    private final AtomicInteger changeBudgetYields;
    private final AtomicInteger edgeBudgetYields;
    private final String operationName;
    private final Thread thread;

    private volatile boolean running = true;

    LightEngineWorker(final LightQueue queue,
                      final Supplier<PulsarEngine> engineFactory,
                      final BiConsumer<ChunkTasks, PulsarEngine> taskProcessor,
                      final AtomicInteger changeBudgetYields,
                      final AtomicInteger edgeBudgetYields,
                      final String operationName,
                      final String threadName,
                      final boolean startThread) {
        this.queue = queue;
        this.engineFactory = engineFactory;
        this.taskProcessor = taskProcessor;
        this.changeBudgetYields = changeBudgetYields;
        this.edgeBudgetYields = edgeBudgetYields;
        this.operationName = operationName;

        if (startThread) {
            this.thread = new Thread(this::run, threadName);
            this.thread.setDaemon(true);
            this.thread.start();
        } else {
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
            this.processPending();
        }
    }

    void processPending() {
        final PulsarEngine engine = this.acquireEngine();
        try {
            final long changeDeadline = System.nanoTime() + BLOCK_CHANGE_BUDGET_NS;
            ChunkTasks task;
            while ((task = this.queue.removeFirstBlockChangeTask()) != null) {
                this.processTask(task, engine);
                if (System.nanoTime() > changeDeadline) {
                    if (LightStats.enabled) {
                        this.changeBudgetYields.incrementAndGet();
                    }
                    break;
                }
            }

            boolean moreWork = true;
            while (moreWork) {
                moreWork = false;
                while ((task = this.queue.removeFirstInitialLightTask()) != null) {
                    this.processTask(task, engine);
                    ChunkTasks priorityTask;
                    while ((priorityTask = this.queue.removeFirstBlockChangeTask()) != null) {
                        this.processTask(priorityTask, engine);
                    }
                }

                final long edgeDeadline = System.nanoTime() + EDGE_CHECK_BUDGET_NS;
                while ((task = this.queue.removeFirstTask()) != null) {
                    this.processTask(task, engine);
                    ChunkTasks priorityTask;
                    while ((priorityTask = this.queue.removeFirstBlockChangeTask()) != null) {
                        this.processTask(priorityTask, engine);
                    }
                    if (this.queue.hasInitialLightTask()) {
                        moreWork = true;
                        break;
                    }
                    if (System.nanoTime() > edgeDeadline) {
                        if (LightStats.enabled) {
                            this.edgeBudgetYields.incrementAndGet();
                        }
                        break;
                    }
                }
            }
        } catch (final Throwable t) {
            Pulsar.LOGGER.error("Exception in " + this.operationName, t);
        } finally {
            this.releaseEngine(engine);
        }
    }

    private void processTask(final ChunkTasks task, final PulsarEngine engine) {
        try {
            this.taskProcessor.accept(task, engine);
        } finally {
            this.queue.completeTask(task);
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
        this.queue.wakeUp();
    }

    void awaitStop() {
        if (this.thread == null) {
            return;
        }
        try {
            this.thread.join(1000L);
        } catch (final InterruptedException ignored) {
        }
    }
}
