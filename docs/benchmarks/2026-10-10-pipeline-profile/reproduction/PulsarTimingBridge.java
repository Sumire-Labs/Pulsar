package com.sumirelabs.lightbench;

import java.lang.reflect.Method;
import java.nio.file.Path;

/** Diagnostic-only reflection bridge; initialization and row collection outside latency timers. */
final class PulsarTimingBridge {
    private static final boolean ENABLED = Boolean.getBoolean("pulsar.phaseTiming");
    private static Method begin, end, write;

    static boolean begin(String phase) throws Exception {
        if (!ENABLED || phase == null) return false;
        if (begin == null) {
            Class<?> c = Class.forName("com.sumirelabs.pulsar.light.PhaseTiming");
            begin = c.getMethod("begin", String.class);
            end = c.getMethod("end", long.class, long.class, long.class, long.class);
            write = c.getMethod("write", Path.class);
        }
        begin.invoke(null, phase);
        return true;
    }

    static void end(long submission, long barrier, long completion, long editStarted) throws Exception {
        end.invoke(null, submission, barrier, completion, editStarted);
    }

    static void write(Path output) throws Exception {
        if (write != null) write.invoke(null, output);
    }
}
