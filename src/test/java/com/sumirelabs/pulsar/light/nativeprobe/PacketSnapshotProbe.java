package com.sumirelabs.pulsar.light.nativeprobe;

public interface PacketSnapshotProbe {
    int snapshotCalls();
    int extractionCalls();
    boolean snapshotReleased();
}
