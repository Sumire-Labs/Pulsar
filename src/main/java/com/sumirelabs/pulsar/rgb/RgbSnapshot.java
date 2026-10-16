package com.sumirelabs.pulsar.rgb;

/** All three channels become visible together through a single volatile chunk reference. */
public final class RgbSnapshot {
    public final int minSection;
    public final long revision;
    private final byte[][][] data;
    private final byte[][] canonical;
    public RgbSnapshot(int minSection, long revision, byte[][][] data) {
        this(minSection, revision, data, project(data));
    }
    public RgbSnapshot(int minSection, long revision, byte[][][] data, byte[][] canonical) {
        if (data.length != 3 || data[0].length != data[1].length || data[0].length != data[2].length
                || data[0].length > 4098) throw new IllegalArgumentException("Invalid RGB section count");
        this.minSection = minSection;
        this.revision = revision;
        if (canonical.length != data[0].length) throw new IllegalArgumentException("Invalid canonical section count");
        this.canonical = new byte[canonical.length][];
        for (int s = 0; s < canonical.length; s++) if (canonical[s] != null) {
            if (canonical[s].length != 2048) throw new IllegalArgumentException("Invalid canonical nibble length");
            this.canonical[s] = canonical[s].clone();
        }
        this.data = new byte[3][data[0].length][];
        for (int c = 0; c < 3; c++) for (int s = 0; s < data[c].length; s++) {
            if (data[c][s] == null) continue;
            if (data[c][s].length != 2048) throw new IllegalArgumentException("Invalid RGB nibble length");
            this.data[c][s] = data[c][s].clone();
        }
    }
    private static byte[][] project(byte[][][] data) {
        byte[][] result = new byte[data[0].length][];
        for (int s = 0; s < result.length; s++) {
            boolean present = false;
            for (int c = 0; c < 3; c++) present |= data[c][s] != null;
            if (!present) continue;
            result[s] = new byte[2048];
            for (int i = 0; i < 2048; i++) {
                int low = 0, high = 0;
                for (int c = 0; c < 3; c++) if (data[c][s] != null) {
                    low = Math.max(low, data[c][s][i] & 15);
                    high = Math.max(high, data[c][s][i] & 0xF0);
                }
                result[s][i] = (byte) (low | high);
            }
        }
        return result;
    }
    public byte[] canonicalSection(int s) { return canonical[s] == null ? null : canonical[s].clone(); }
    public boolean sameLightData(RgbSnapshot other) {
        return minSection == other.minSection && java.util.Arrays.deepEquals(data, other.data)
                && java.util.Arrays.deepEquals(canonical, other.canonical);
    }
    public int sectionCount() { return data[0].length; }
    public byte[] section(int c, int s) { return data[c][s] == null ? null : data[c][s].clone(); }
    public int get(int x, int y, int z) {
        int s = (y >> 4) - minSection;
        if (s < 0 || s >= sectionCount()) return 0;
        int index = (x & 15) | (z & 15) << 4 | (y & 15) << 8;
        int rgb = 0;
        for (int c = 0; c < 3; c++) {
            byte[] b = data[c][s];
            int value = b == null ? 0 : (b[index >> 1] & 255) >> ((index & 1) << 2) & 15;
            rgb |= value << ((2 - c) << 2);
        }
        return rgb;
    }
}
