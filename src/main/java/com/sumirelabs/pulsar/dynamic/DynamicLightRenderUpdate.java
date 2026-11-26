package com.sumirelabs.pulsar.dynamic;

/** Tracks whether only moving lights have dirtied an already built vanilla terrain mesh. */
public interface DynamicLightRenderUpdate {

    boolean pulsar$onlyDynamicLight();

    boolean pulsar$rebuildInProgress();

    boolean pulsar$resortPending();

    void pulsar$setResortPending(boolean pending);

}
