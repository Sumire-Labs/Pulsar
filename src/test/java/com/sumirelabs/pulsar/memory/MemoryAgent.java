package com.sumirelabs.pulsar.memory;

import java.lang.instrument.Instrumentation;

/** Test-only instrumentation; never included in the mod artifact. */
public final class MemoryAgent {
    private static Instrumentation instrumentation;
    public static void premain(String args,Instrumentation instance){instrumentation=instance;}
    public static long size(Object object){return instrumentation.getObjectSize(object);}
}
