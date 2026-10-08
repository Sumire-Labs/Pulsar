package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.Pulsar;
import com.sumirelabs.pulsar.config.PulsarConfig;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-world stats accumulator for light engine instrumentation. Only active
 * while {@code debug.enableDebugStats} is set; dumps to
 * {@code logs/pulsar-stats.log} every {@value LOG_INTERVAL_TICKS} ticks.
 */
public final class LightStats {

    private static final int LOG_INTERVAL_TICKS = 20;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS");
    private final Thread owner = Thread.currentThread();

    /**
     * Mirror of {@link PulsarConfig.Debug#enableDebugStats}, refreshed once
     * per tick so hot paths pay a single volatile load when stats are off.
     */
    public static volatile boolean enabled;
    // Client render-mark stat (main thread only)
    public static long engineRenderMarks;
    public static long engineRenderRequests;
    // Block change diagnostics (block worker thread)
    public final AtomicLong blockPositionsProcessed = new AtomicLong();
    // Edge check diagnostics (multi-thread write, public for cross-package access from engine)
    public final AtomicLong edgeSectionPairsChecked = new AtomicLong();
    public final AtomicLong edgeSectionPairsSkippedFull = new AtomicLong();
    public final AtomicLong edgeSectionPairsSkippedZero = new AtomicLong();
    public final AtomicLong edgeBlocksTotal = new AtomicLong();
    public final AtomicLong edgeBlocksSkippedTrivial = new AtomicLong();
    public final AtomicLong edgeBlocksSkippedConsistency = new AtomicLong();
    public final AtomicLong edgeBlocksRecalculated = new AtomicLong();
    public final AtomicLong edgeBlocksMismatched = new AtomicLong();
    // Worker stats — written by sky and block worker threads concurrently
    final AtomicLong chunksProcessed = new AtomicLong();
    final AtomicLong initialLightsRun = new AtomicLong();
    final AtomicLong skyWorkerTimeNs = new AtomicLong();
    final AtomicLong blockWorkerTimeNs = new AtomicLong();
    final AtomicLong skyTaskMaxNs = new AtomicLong();
    final AtomicLong blockTaskMaxNs = new AtomicLong();
    final AtomicLong skyTasksProcessed = new AtomicLong();
    final AtomicLong blockTasksProcessed = new AtomicLong();
    // Budget yield stats (multi-thread write)
    final AtomicInteger blockBudgetYields = new AtomicInteger();
    final AtomicInteger skyBudgetYields = new AtomicInteger();
    // Main-thread server chunk unload diagnostics.
    final AtomicLong unloadWaitNs = new AtomicLong();
    final AtomicLong unloadWaitMaxNs = new AtomicLong();
    final AtomicInteger unloadWaitTimeouts = new AtomicInteger();
    final AtomicInteger unloadWaitBudgetExhausted = new AtomicInteger();
    final AtomicInteger unloadLightInvalidations = new AtomicInteger();
    // Queue stats (multi-thread write)
    final AtomicInteger chunksQueued = new AtomicInteger();
    private final String side;
    private final int dimension;
    final AtomicLong maxQueueLatencyNs = new AtomicLong();
    final AtomicLong totalQueueLatencyNs = new AtomicLong();
    private final AtomicLong queueLatencySamples = new AtomicLong();
    // These operations run only on this world's main thread.
    private final DurationWindow sampleLoad = new DurationWindow();
    private final DurationWindow sampleFlush = new DurationWindow();
    private final DurationWindow clientDrain = new DurationWindow();
    private long clientOvershootMaxNs;
    // Backlog snapshots (main thread only)
    volatile int skyBacklog;
    volatile int blockBacklog;
    private PrintWriter writer;
    private boolean writerFailed;
    private boolean pendingReset;
    // Tick tracking
    private long tickCount;
    private long windowStartTick;

    public LightStats(final boolean isClient, final int dimension) {
        this(isClient, dimension, null);
    }

    LightStats(final boolean isClient, final int dimension, final PrintWriter writer) {
        this.side = isClient ? "CLIENT" : "SERVER";
        this.dimension = dimension;
        this.writer = writer;
    }

    /**
     * Called once per tick from the main thread. Triggers periodic dump.
     */
    public void tick(final int skyBacklog, final int blockBacklog) {
        if (Thread.currentThread() != this.owner) {
            throw new IllegalStateException("Light statistics must advance on the world thread");
        }
        final boolean on = PulsarConfig.debug.enableDebugStats;
        if (enabled != on) {
            enabled = on;
        }
        this.tickCount++;
        if (!on) {
            this.pendingReset = true;
            return;
        }
        if (this.pendingReset) {
            // Discard partial data recorded around a runtime toggle.
            this.pendingReset = false;
            reset();
        }
        this.skyBacklog = skyBacklog;
        this.blockBacklog = blockBacklog;
        if (this.tickCount - this.windowStartTick >= LOG_INTERVAL_TICKS) {
            dump();
            reset();
        }
    }

    void recordQueueLatency(final long enqueueTimeNs) {
        if (enqueueTimeNs == 0L) return; // task was created while stats were off
        final long latency = System.nanoTime() - enqueueTimeNs;
        this.maxQueueLatencyNs.accumulateAndGet(latency, Math::max);
        this.totalQueueLatencyNs.addAndGet(latency);
        this.queueLatencySamples.incrementAndGet();
    }

    void recordSampleLoad(final long elapsedNs) { this.sampleLoad.record(elapsedNs); }
    void recordSampleFlush(final long elapsedNs) { this.sampleFlush.record(elapsedNs); }

    void recordClientDrain(final long elapsedNs, final long budgetNs) {
        this.clientDrain.record(elapsedNs);
        this.clientOvershootMaxNs = Math.max(this.clientOvershootMaxNs, elapsedNs - budgetNs);
    }

    void recordUnloadWait(final long elapsedNs) {
        if (!enabled) return;
        this.unloadWaitNs.addAndGet(elapsedNs);
        this.unloadWaitMaxNs.updateAndGet(current -> Math.max(current, elapsedNs));
    }

    void recordUnloadWaitTimeout() {
        if (enabled) this.unloadWaitTimeouts.incrementAndGet();
    }

    void recordUnloadWaitBudgetExhausted() {
        if (enabled) this.unloadWaitBudgetExhausted.incrementAndGet();
    }

    void recordUnloadLightInvalidation() {
        if (enabled) this.unloadLightInvalidations.incrementAndGet();
    }

    private void dump() {
        if (this.writer == null) {
            if (this.writerFailed) return;
            try {
                final File logFile = new File("logs/pulsar-stats.log");
                logFile.getParentFile().mkdirs();
                this.writer = new PrintWriter(new FileWriter(logFile, true), true);
            } catch (final IOException e) {
                this.writerFailed = true;
                Pulsar.LOGGER.error("Failed to open pulsar-stats.log", e);
                return;
            }
        }

        final long processed = this.chunksProcessed.get();
        final int queued = this.chunksQueued.get();
        final StringBuilder sb = new StringBuilder(256);
        sb.append(this.timeFormat.format(new Date()));
        sb.append(" [").append(this.side).append(']');
        sb.append(" dimension=").append(this.dimension);
        sb.append(" ticks=").append(this.windowStartTick).append('-').append(this.tickCount);
        sb.append(" queued=").append(queued);
        sb.append(" processed=").append(processed);
        sb.append(" initial=").append(this.initialLightsRun.get());
        sb.append(" skyMs=").append(String.format(Locale.US, "%.1f", this.skyWorkerTimeNs.get() / 1_000_000.0));
        sb.append(" blockMs=").append(String.format(Locale.US, "%.1f", this.blockWorkerTimeNs.get() / 1_000_000.0));
        sb.append(" skyTaskMaxMs=").append(String.format(Locale.US, "%.3f", this.skyTaskMaxNs.get() / 1_000_000.0));
        sb.append(" blockTaskMaxMs=").append(String.format(Locale.US, "%.3f", this.blockTaskMaxNs.get() / 1_000_000.0));
        sb.append(" skyTasks=").append(this.skyTasksProcessed.get());
        sb.append(" blockTasks=").append(this.blockTasksProcessed.get());
        sb.append(" blockPos=").append(this.blockPositionsProcessed.get());
        final int skyBl = this.skyBacklog;
        final int blockBl = this.blockBacklog;
        sb.append(" skyBacklog=").append(skyBl);
        sb.append(" blockBacklog=").append(blockBl);
        final int blockYields = this.blockBudgetYields.get();
        if (blockYields > 0) {
            sb.append(" blockBudgetYields=").append(blockYields);
        }
        final int skyYields = this.skyBudgetYields.get();
        if (skyYields > 0) {
            sb.append(" skyBudgetYields=").append(skyYields);
        }

        final long latencySamples = this.queueLatencySamples.get();
        if (latencySamples > 0) {
            sb.append(" avgLatencyMs=").append(String.format(Locale.US, "%.1f", (this.totalQueueLatencyNs.get() / (double) latencySamples) / 1_000_000.0));
        }
        if (this.maxQueueLatencyNs.get() > 0) {
            sb.append(" maxLatencyMs=").append(String.format(Locale.US, "%.1f", this.maxQueueLatencyNs.get() / 1_000_000.0));
        }

        if (this.unloadWaitNs.get() > 0 || this.unloadWaitTimeouts.get() > 0
                || this.unloadWaitBudgetExhausted.get() > 0 || this.unloadLightInvalidations.get() > 0) {
            sb.append(" unloadWaitMs=").append(String.format(Locale.US, "%.1f", this.unloadWaitNs.get() / 1_000_000.0));
            sb.append(" unloadWaitMaxMs=").append(String.format(Locale.US, "%.2f", this.unloadWaitMaxNs.get() / 1_000_000.0));
            sb.append(" unloadTimeouts=").append(this.unloadWaitTimeouts.get());
            sb.append(" unloadBudgetExhausted=").append(this.unloadWaitBudgetExhausted.get());
            sb.append(" unloadLightInvalidations=").append(this.unloadLightInvalidations.get());
        }

        final long edgePairs = this.edgeSectionPairsChecked.get();
        if (edgePairs > 0) {
            sb.append(" edgeStats sectionPairs=").append(edgePairs);
            sb.append(" skippedFull=").append(this.edgeSectionPairsSkippedFull.get());
            sb.append(" skippedZero=").append(this.edgeSectionPairsSkippedZero.get());
            sb.append(" blocks=").append(this.edgeBlocksTotal.get());
            sb.append(" skippedTrivial=").append(this.edgeBlocksSkippedTrivial.get());
            sb.append(" skippedConsistency=").append(this.edgeBlocksSkippedConsistency.get());
            sb.append(" recalc=").append(this.edgeBlocksRecalculated.get());
            sb.append(" mismatched=").append(this.edgeBlocksMismatched.get());
        }

        if ("CLIENT".equals(this.side)) {
            sb.append(" engineMarks=").append(engineRenderMarks);
            sb.append(" engineRequests=").append(engineRenderRequests);
            this.clientDrain.append(sb, "clientDrain");
            sb.append(" clientOvershootMaxMs=").append(String.format(Locale.US, "%.3f", this.clientOvershootMaxNs / 1_000_000.0));
        } else {
            this.sampleLoad.append(sb, "sampleLoad");
            this.sampleFlush.append(sb, "sampleFlush");
        }

        this.writer.println(sb);
    }

    private void reset() {
        this.windowStartTick = this.tickCount;
        this.chunksProcessed.set(0);
        this.initialLightsRun.set(0);
        this.skyWorkerTimeNs.set(0);
        this.blockWorkerTimeNs.set(0);
        this.skyTaskMaxNs.set(0);
        this.blockTaskMaxNs.set(0);
        this.skyTasksProcessed.set(0);
        this.blockTasksProcessed.set(0);
        this.maxQueueLatencyNs.set(0);
        this.totalQueueLatencyNs.set(0);
        this.queueLatencySamples.set(0);
        if ("CLIENT".equals(this.side)) {
            engineRenderMarks = 0;
            engineRenderRequests = 0;
        }
        this.sampleLoad.reset();
        this.sampleFlush.reset();
        this.clientDrain.reset();
        this.clientOvershootMaxNs = 0;
        this.blockBudgetYields.set(0);
        this.skyBudgetYields.set(0);
        this.unloadWaitNs.set(0);
        this.unloadWaitMaxNs.set(0);
        this.unloadWaitTimeouts.set(0);
        this.unloadWaitBudgetExhausted.set(0);
        this.unloadLightInvalidations.set(0);
        this.chunksQueued.set(0);
        this.blockPositionsProcessed.set(0);
        this.edgeSectionPairsChecked.set(0);
        this.edgeSectionPairsSkippedFull.set(0);
        this.edgeSectionPairsSkippedZero.set(0);
        this.edgeBlocksTotal.set(0);
        this.edgeBlocksSkippedTrivial.set(0);
        this.edgeBlocksSkippedConsistency.set(0);
        this.edgeBlocksRecalculated.set(0);
        this.edgeBlocksMismatched.set(0);
    }

    public void close() {
        if (this.writer != null) {
            this.writer.close();
        }
    }

    private static final class DurationWindow {
        private long count, totalNs, maxNs;

        void record(final long elapsedNs) {
            this.count++;
            this.totalNs += elapsedNs;
            this.maxNs = Math.max(this.maxNs, elapsedNs);
        }

        void append(final StringBuilder sb, final String label) {
            sb.append(' ').append(label).append("Count=").append(this.count);
            sb.append(' ').append(label).append("Ms=").append(String.format(Locale.US, "%.3f", this.totalNs / 1_000_000.0));
            sb.append(' ').append(label).append("MaxMs=").append(String.format(Locale.US, "%.3f", this.maxNs / 1_000_000.0));
        }

        void reset() { this.count = this.totalNs = this.maxNs = 0; }
    }
}
