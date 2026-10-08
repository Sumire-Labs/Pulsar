package com.sumirelabs.pulsar.light;

/** Zero retains dedicated workers; -1 selects ScalableLux's CPU/3 policy. */
public final class LightThreadCount {
    private LightThreadCount() {}

    public static int resolve(int configured, int processors) {
        return configured < 0 ? Math.max(1, Math.min(16, processors / 3))
                : Math.max(0, Math.min(16, configured));
    }
}
