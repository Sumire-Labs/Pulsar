package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.Pulsar;
import java.util.HashSet;

/** One diagnostic per source class, at most 64 classes per process. Never catches fatal VM errors. */
public final class LightCallbackFailures {
    private static final HashSet<Class<?>> REPORTED = new HashSet<>();
    private LightCallbackFailures() {}
    public static synchronized void report(Object source, Throwable error) {
        Class<?> type=source.getClass();
        if(REPORTED.size()<64 && REPORTED.add(type))
            Pulsar.LOGGER.warn("Light callback failed for {}; keeping the previous/static sample and retrying on the world thread",type.getName(),error);
    }
}
