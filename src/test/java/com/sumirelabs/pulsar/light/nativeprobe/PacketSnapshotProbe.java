/*
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.sumirelabs.pulsar.light.nativeprobe;

public interface PacketSnapshotProbe {

    int snapshotCalls();
    int extractionCalls();
    boolean snapshotReleased();

}
