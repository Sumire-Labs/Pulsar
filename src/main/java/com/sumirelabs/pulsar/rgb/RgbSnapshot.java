package com.sumirelabs.pulsar.rgb;

/** All three channels become visible together through a single volatile chunk reference. */
public final class RgbSnapshot {
    private static final byte[] FULL = new byte[2048];
    static { java.util.Arrays.fill(FULL, (byte)0xFF); }
    // Immutable constant planes are shared across chunks, never live SWMR storage.
    private static final java.util.concurrent.atomic.AtomicReferenceArray<byte[]> CONSTANTS =
            new java.util.concurrent.atomic.AtomicReferenceArray<>(16);
    static { CONSTANTS.set(15,FULL); }
    static byte[] saturatedPlane() { return FULL; }
    public final int minSection;
    public final long revision;
    private final byte[][][] data;
    private final byte[][] canonical;
    private final boolean maxProjection;

    /** Read-only section views retain only their own immutable planes, not older whole-chunk snapshots. */
    ShaderSection shaderSection(int sectionY, ShaderSection previous) {
        int section = sectionY - minSection;
        if (section < 0 || section >= canonical.length) return ShaderSection.ZERO;
        byte[] r = data[0][section], g = data[1][section], b = data[2][section], c = canonical[section];
        if (previous != null && previous.r == r && previous.g == g && previous.b == b && previous.canonical == c) return previous;
        return new ShaderSection(r, g, b, c);
    }
    static final class ShaderSection {
        static final ShaderSection ZERO = new ShaderSection(null, null, null, null);
        private final byte[] r, g, b, canonical;
        private final int constant;
        private ShaderSection(byte[] r, byte[] g, byte[] b, byte[] canonical) {
            this.r = r; this.g = g; this.b = b; this.canonical = canonical;
            int rr = uniform(r), gg = uniform(g), bb = uniform(b), cc = uniform(canonical);
            constant = (rr | gg | bb | cc) < 0 ? -1 : rr << 8 | gg << 4 | bb | cc << 12;
        }
        static ShaderSection white(byte[] owned) { return owned == null ? ZERO : new ShaderSection(owned, owned, owned, owned); }
        private static int uniform(byte[] values) {
            if (values == null) return 0;
            int first = values[0] & 255;
            if ((first & 15) != first >>> 4) return -1;
            for (byte value : values) if ((value & 255) != first) return -1;
            return first & 15;
        }
        private static int read(byte[] values, int at, int shift) { return values == null ? 0 : values[at] >>> shift & 15; }
        int cell(int local) {
            if (constant >= 0) return constant;
            int at = local >>> 1, shift = (local & 1) * 4;
            return read(r, at, shift) << 8 | read(g, at, shift) << 4 | read(b, at, shift) | read(canonical, at, shift) << 12;
        }
        int constant() { return constant; }
    }

    public RgbSnapshot(int minSection, long revision, byte[][][] data) {
        this(minSection, revision, data, project(data), true, true);
    }
    public RgbSnapshot(int minSection, long revision, byte[][][] data, byte[][] canonical) {
        this(minSection, revision, data, canonical, true);
    }
    private RgbSnapshot(int minSection, long revision, byte[][][] data, byte[][] canonical, boolean copy) {
        this(minSection, revision, data, canonical, copy, false);
    }
    private RgbSnapshot(int minSection, long revision, byte[][][] data, byte[][] canonical, boolean copy, boolean maxProjection) {
        this.maxProjection = maxProjection;
        if (data.length != 3 || data[0].length != data[1].length || data[0].length != data[2].length
                || data[0].length > 4098) throw new IllegalArgumentException("Invalid RGB section count");
        this.minSection = minSection;
        this.revision = revision;
        if (canonical.length != data[0].length) throw new IllegalArgumentException("Invalid canonical section count");
        this.canonical = copy ? new byte[canonical.length][] : canonical;
        for (int s = 0; s < canonical.length; s++) if (canonical[s] != null) {
            if (canonical[s].length != 2048) throw new IllegalArgumentException("Invalid canonical nibble length");
            if (copy) this.canonical[s] = s>0 && canonical[s]==canonical[s-1]
                    ? this.canonical[s-1] : retain(canonical[s], null);
        }
        this.data = copy ? new byte[3][data[0].length][] : data;
        for (int c = 0; c < 3; c++) for (int s = 0; s < data[c].length; s++) {
            if (data[c][s] == null) continue;
            if (data[c][s].length != 2048) throw new IllegalArgumentException("Invalid RGB nibble length");
            if (copy) this.data[c][s] = s>0 && data[c][s]==data[c][s-1]
                    ? this.data[c][s-1] : share(data[c][s], this.canonical[s], this.data, c, s, null);
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
            canonical[s] = s>0 && scalar[s]==scalar[s-1] ? canonical[s-1]
                    : retain(scalar[s], compatible ? previous.canonical[s] : null);
            for (int c = 0; c < 3; c++) owned[c][s] = s>0 && live[c][s]==live[c][s-1] ? owned[c][s-1]
                    : share(live[c][s], canonical[s], owned, c, s, compatible ? previous.data[c][s] : null);
        }
        return new RgbSnapshot(minSection, revision, owned, canonical, false);
    }
    /** Build canonical light from RGB, without another propagation pass. Retain only owned immutable bytes. */
    static RgbSnapshot captureMax(int minSection, long revision, byte[][][] live, RgbSnapshot previous) {
        int count = live[0].length;
        boolean compatible = previous != null && previous.minSection == minSection && previous.sectionCount() == count;
        byte[][][] owned = new byte[3][count][];
        byte[][] canonical = new byte[count][];
        for (int s = 0; s < count; s++) {
            boolean unchanged = compatible && previous.maxProjection;
            for (int c = 0; c < 3; c++) {
                owned[c][s] = s>0 && live[c][s]==live[c][s-1] ? owned[c][s-1]
                        : share(live[c][s], compatible ? previous.canonical[s] : null, owned, c, s,
                                compatible ? previous.data[c][s] : null);
                unchanged &= compatible && owned[c][s] == previous.data[c][s];
            }
            if (unchanged) canonical[s] = previous.canonical[s];
            else {
                byte[] r = owned[0][s], g = owned[1][s], b = owned[2][s];
                if (r == FULL || g == FULL || b == FULL) canonical[s] = FULL;
                else if (r == g && g == b) canonical[s] = r;
                else if (g == null && b == null) canonical[s] = r;
                else if (r == null && b == null) canonical[s] = g;
                else if (r == null && g == null) canonical[s] = b;
                else {
                    byte[] projected = new byte[2048];
                    for (int i = 0; i < projected.length; i++) {
                        int rv = r == null ? 0 : r[i] & 255, gv = g == null ? 0 : g[i] & 255, bv = b == null ? 0 : b[i] & 255;
                        projected[i] = (byte)(Math.max(rv & 15, Math.max(gv & 15, bv & 15))
                                | Math.max(rv & 240, Math.max(gv & 240, bv & 240)));
                    }
                    canonical[s] = java.util.Arrays.equals(projected,r) ? r
                            : java.util.Arrays.equals(projected,g) ? g
                            : java.util.Arrays.equals(projected,b) ? b
                            : retain(projected, compatible ? previous.canonical[s] : null);
                }
            }
        }
        return new RgbSnapshot(minSection, revision, owned, canonical, false, true);
    }
    private static byte[] retain(byte[] live, byte[] previous) {
        if (java.util.Arrays.equals(live, previous)) return previous;
        if (live == null) return null;
        int value=live[0]&255;
        if((value&15)==value>>>4) {
            boolean constant=true;
            for(byte b:live)if((b&255)!=value){constant=false;break;}
            if(constant) {
                int level=value&15;
                byte[] cached=CONSTANTS.get(level);
                if(cached!=null)return cached;
                byte[] owned=live.clone();
                return CONSTANTS.compareAndSet(level,null,owned) ? owned : CONSTANTS.get(level);
            }
        }
        return live.clone();
    }
    /** Deduplicate only against immutable planes in this publication or its predecessor.
     * No global content map, no retained old snapshots, and no aliases to mutable input. */
    private static byte[] share(byte[] live,byte[] canonical,byte[][][] owned,int channel,int section,byte[] previous) {
        if(java.util.Arrays.equals(live,canonical))return canonical;
        for(int c=0;c<channel;c++)if(java.util.Arrays.equals(live,owned[c][section]))return owned[c][section];
        return retain(live,previous);
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
    boolean canonicalAbsent(int s) { return canonical[s] == null; }
    boolean matchesCanonical(int s,byte[] visible) { return java.util.Arrays.equals(canonical[s],visible); }
    com.sumirelabs.pulsar.light.SWMRNibbleArray canonicalNibble(int s) {
        return com.sumirelabs.pulsar.light.SWMRNibbleArray.fromImmutable(canonical[s]);
    }
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
    public boolean sameCanonical(RgbSnapshot other) {
        return other != null && minSection == other.minSection && java.util.Arrays.deepEquals(canonical, other.canonical);
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
