package com.sumirelabs.pulsar.rgb;

import com.sumirelabs.pulsar.light.SWMRNibbleArray;

/** Mutable engine storage, written only inside the existing block-lane reservation. */
public final class RgbStorage {
    public final SWMRNibbleArray[][] channels = new SWMRNibbleArray[3][];
    public final boolean[][] empty = new boolean[3][];
    public final int minSection;
    public RgbStorage(int minSection, int count) {
        this.minSection = minSection;
        for (int c = 0; c < 3; c++) {
            channels[c] = new SWMRNibbleArray[count];
            for (int s = 0; s < count; s++) channels[c][s] = new SWMRNibbleArray(null, true);
        }
    }
    public RgbSnapshot snapshot(long revision) { return snapshot(revision, null); }
    public RgbSnapshot snapshot(long revision, SWMRNibbleArray[] canonical) {
        byte[][][] data = new byte[3][channels[0].length][];
        for (int c = 0; c < 3; c++) for (int s = 0; s < data[c].length; s++) {
            SWMRNibbleArray n = channels[c][s];
            byte[] bytes = n == null ? null : n.getVisibleData();
            if (bytes != null) {
                boolean nonzero = false;
                for (byte b : bytes) if (b != 0) { nonzero = true; break; }
                if (nonzero) data[c][s] = bytes;
            }
        }
        if (canonical == null) return new RgbSnapshot(minSection, revision, data);
        byte[][] scalar = new byte[channels[0].length][];
        for (int i = 0; i < scalar.length; i++) if (i < canonical.length && canonical[i] != null) {
            byte[] bytes = canonical[i].getVisibleData();
            if (bytes != null) for (byte b : bytes) if (b != 0) { scalar[i] = bytes; break; }
        }
        return new RgbSnapshot(minSection, revision, data, scalar);
    }
}
