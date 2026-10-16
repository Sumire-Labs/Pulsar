package com.sumirelabs.pulsar.rgb;

/** All three channels become visible together through a single volatile chunk reference. */
public final class RgbSnapshot {
    private static final byte[] FULL = new byte[2048];
    static { java.util.Arrays.fill(FULL, (byte)0xFF); }
    static byte[] saturatedPlane() { return FULL; }
    public final int minSection;
    public final long revision;
    private final byte[][][] data;
    private final byte[][] canonical;
    public RgbSnapshot(int minSection, long revision, byte[][][] data) {
        this(minSection, revision, data, project(data));
    }
    public RgbSnapshot(int minSection, long revision, byte[][][] data, byte[][] canonical) {
        this(minSection, revision, data, canonical, true);
    }
    private RgbSnapshot(int minSection, long revision, byte[][][] data, byte[][] canonical, boolean copy) {
        if (data.length != 3 || data[0].length != data[1].length || data[0].length != data[2].length
                || data[0].length > 4098) throw new IllegalArgumentException("Invalid RGB section count");
        this.minSection = minSection;
        this.revision = revision;
        if (canonical.length != data[0].length) throw new IllegalArgumentException("Invalid canonical section count");
        this.canonical = new byte[canonical.length][];
        for (int s = 0; s < canonical.length; s++) if (canonical[s] != null) {
            if (canonical[s].length != 2048) throw new IllegalArgumentException("Invalid canonical nibble length");
            this.canonical[s] = copy ? canonical[s].clone() : canonical[s];
        }
        this.data = new byte[3][data[0].length][];
        for (int c = 0; c < 3; c++) for (int s = 0; s < data[c].length; s++) {
            if (data[c][s] == null) continue;
            if (data[c][s].length != 2048) throw new IllegalArgumentException("Invalid RGB nibble length");
            this.data[c][s] = copy ? data[c][s].clone() : data[c][s];
        }
    }
    /** Reuse only immutable bytes owned by a previous publication. Live worker bytes
     * are always compared and cloned, since SWMR visible arrays can be reused in place. */
    static RgbSnapshot capture(int minSection, long revision, byte[][][] live, byte[][] scalar, RgbSnapshot previous) {
        boolean compatible = previous != null && previous.minSection == minSection
                && previous.sectionCount() == scalar.length;
        byte[][][] owned = new byte[3][scalar.length][];
        byte[][] canonical = new byte[scalar.length][];
        for (int s = 0; s < scalar.length; s++) {
            canonical[s] = retain(scalar[s], compatible ? previous.canonical[s] : null);
            for (int c = 0; c < 3; c++) owned[c][s] = retain(live[c][s], compatible ? previous.data[c][s] : null);
        }
        return new RgbSnapshot(minSection, revision, owned, canonical, false);
    }
    private static byte[] retain(byte[] live, byte[] previous) {
        if (java.util.Arrays.equals(live, previous)) return previous;
        if (isFull(live)) return FULL;
        return live == null ? null : live.clone();
    }
    private static boolean isFull(byte[] bytes) {
        if (bytes == null) return false;
        if (bytes == FULL) return true;
        for (byte value : bytes) if (value != (byte)0xFF) return false;
        return true;
    }
    /** Encode immutable planes directly; saturated sky planes need only mask bits. */
    void writeSection(io.netty.buffer.ByteBuf out, int section) {
        int present = 0, full = 0;
        for (int c = 0; c < 4; c++) {
            byte[] bytes = c == 3 ? canonical[section] : data[c][section];
            if (bytes != null) { present |= 1 << c; if (isFull(bytes)) full |= 1 << c; }
        }
        out.writeByte(present | full << 4);
        for (int c = 0; c < 4; c++) if ((present & 1 << c) != 0 && (full & 1 << c) == 0)
            out.writeBytes(c == 3 ? canonical[section] : data[c][section]);
    }
    static byte[] readPlane(io.netty.buffer.ByteBuf in, boolean full) {
        if (full) return FULL;
        byte[] bytes = new byte[2048]; in.readBytes(bytes); return bytes;
    }
    public int canonical(int x, int y, int z) {
        int s = (y >> 4) - minSection;
        if (s < 0 || s >= canonical.length || canonical[s] == null) return 0;
        int index = (x & 15) | (z & 15) << 4 | (y & 15) << 8;
        return (canonical[s][index >> 1] & 255) >> ((index & 1) << 2) & 15;
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
    public boolean canonicalFull(int s) { return isFull(canonical[s]); }
    public void copyCanonicalTo(int s, byte[] target) {
        if (target.length != 2048) throw new IllegalArgumentException("Invalid canonical target");
        if (canonical[s] == null) java.util.Arrays.fill(target, (byte)0);
        else System.arraycopy(canonical[s], 0, target, 0, 2048);
    }
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
