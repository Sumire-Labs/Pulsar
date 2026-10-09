package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.config.PulsarConfig;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LightStatsTest {
    @Test
    void loggingIntervalCanChangeAtRuntimeAndCannotBecomeZero() {
        final boolean previous = PulsarConfig.debug.enableDebugStats;
        final int previousInterval = PulsarConfig.debug.statsLogIntervalTicks;
        try {
            PulsarConfig.debug.enableDebugStats = true;
            PulsarConfig.debug.statsLogIntervalTicks = 5;
            final StringWriter output = new StringWriter();
            final LightStats stats = new LightStats(false, 0, new PrintWriter(output));
            for (int i = 0; i < 4; i++) stats.tick(0, 0);
            assertEquals("", output.toString());
            stats.tick(0, 0);
            assertEquals(1, output.toString().lines().count());
            PulsarConfig.debug.statsLogIntervalTicks = 2;
            stats.tick(0, 0);
            assertEquals(1, output.toString().lines().count());
            stats.tick(0, 0);
            assertEquals(2, output.toString().lines().count());
            PulsarConfig.debug.statsLogIntervalTicks = 0;
            stats.tick(0, 0);
            assertEquals(3, output.toString().lines().count());
            PulsarConfig.debug.enableDebugStats = false;
            stats.tick(0, 0);
            assertEquals(3, output.toString().lines().count());
        } finally {
            PulsarConfig.debug.enableDebugStats = previous;
            PulsarConfig.debug.statsLogIntervalTicks = previousInterval;
            LightStats.enabled = previous;
        }
    }

    @Test
    void logsOncePerTwentyTicksAndSeparatesMainThreadWorkFromQueueLatency() {
        final boolean previous = PulsarConfig.debug.enableDebugStats;
        try {
            PulsarConfig.debug.enableDebugStats = true;
            final StringWriter output = new StringWriter();
            final LightStats stats = new LightStats(false, -1, new PrintWriter(output));
            stats.recordSampleLoad(7_000_000L);
            stats.recordSampleLoad(3_000_000L);
            for (int tick = 0; tick < 19; tick++) stats.tick(0, 0);
            assertEquals("", output.toString());
            stats.tick(0, 0);
            final String first = output.toString();
            assertTrue(first.contains("[SERVER] dimension=-1 ticks=0-20"));
            assertTrue(first.contains("sampleLoadCount=2 sampleLoadMs=10.000 sampleLoadMaxMs=7.000"));
            for (int tick = 0; tick < 20; tick++) stats.tick(0, 0);
            assertEquals(2, output.toString().lines().count());
            assertTrue(output.toString().contains("sampleLoadCount=0 sampleLoadMs=0.000"));
        } finally {
            PulsarConfig.debug.enableDebugStats = previous;
            LightStats.enabled = previous;
        }
    }

    @Test
    void serverStatsCannotEraseClientRenderMarksOrAdvanceFromAWorker() throws Exception {
        final boolean previous = PulsarConfig.debug.enableDebugStats;
        final long previousMarks = LightStats.engineRenderMarks;
        try {
            PulsarConfig.debug.enableDebugStats = true;
            final LightStats stats = new LightStats(false, 0, new PrintWriter(new StringWriter()));
            LightStats.engineRenderMarks = 23;
            for (int tick = 0; tick < 20; tick++) stats.tick(0, 0);
            assertEquals(23, LightStats.engineRenderMarks);
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final Thread worker = new Thread(() -> {
                try { stats.tick(0, 0); } catch (Throwable t) { failure.set(t); }
            });
            worker.start();
            worker.join();
            assertInstanceOf(IllegalStateException.class, failure.get());
        } finally {
            PulsarConfig.debug.enableDebugStats = previous;
            LightStats.enabled = previous;
            LightStats.engineRenderMarks = previousMarks;
        }
    }

    @Test
    void recordsClientDrainOvershootSeparatelyFromTaskAndQueueTime() {
        final boolean previous = PulsarConfig.debug.enableDebugStats;
        final long previousMarks = LightStats.engineRenderMarks;
        try {
            PulsarConfig.debug.enableDebugStats = true;
            final StringWriter output = new StringWriter();
            final LightStats stats = new LightStats(true, 0, new PrintWriter(output));
            stats.recordClientDrain(12_000_000L, 2_000_000L);
            stats.recordClientDrain(1_000_000L, 2_000_000L);
            for (int tick = 0; tick < 20; tick++) stats.tick(0, 0);
            assertTrue(output.toString().contains("clientDrainCount=2 clientDrainMs=13.000 clientDrainMaxMs=12.000"));
            assertTrue(output.toString().contains("clientOvershootMaxMs=10.000"));
        } finally {
            PulsarConfig.debug.enableDebugStats = previous;
            LightStats.enabled = previous;
            LightStats.engineRenderMarks = previousMarks;
        }
    }
}
