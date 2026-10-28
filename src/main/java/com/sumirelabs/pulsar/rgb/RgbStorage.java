package com.sumirelabs.pulsar.rgb;

import com.sumirelabs.pulsar.light.SWMRNibbleArray;

/** Mutable engine storage, written only inside its lighting-lane reservation. */
public final class RgbStorage {
    private static final byte[] FULL_SKY = RgbSnapshot.saturatedPlane();
    public final SWMRNibbleArray[][] channels = new SWMRNibbleArray[3][];
    public final boolean[][] empty = new boolean[3][];
    public final int minSection;
    public RgbStorage(int minSection, int count) {
        this(minSection,count,true);
    }
    private RgbStorage(int minSection,int count,boolean initialize) {
        this.minSection = minSection;
        for (int c = 0; c < 3; c++) {
            channels[c] = new SWMRNibbleArray[count];
            if(initialize)for (int s = 0; s < count; s++) channels[c][s] = new SWMRNibbleArray(null, true);
        }
    }
    public RgbSnapshot snapshot(long revision) { return snapshot(revision, null); }
    public RgbSnapshot maximumSnapshot(long revision, boolean sky, RgbSnapshot previous) {
        byte[][][] data = new byte[3][channels[0].length][];
        for (int c = 0; c < 3; c++) {
            if (sky) data[c] = resolveSky(channels[c]);
            else for (int s = 0; s < data[c].length; s++) {
                SWMRNibbleArray nibble = channels[c][s];
                byte[] bytes = nibble == null ? null : nibble.getVisibleData();
                if (bytes != null) for (byte value : bytes) if (value != 0) { data[c][s] = bytes; break; }
            }
        }
        return RgbSnapshot.captureMax(minSection, revision, data, previous);
    }
    public static RgbSnapshot whiteSkySnapshot(int minSection, long revision, SWMRNibbleArray[] canonical, RgbSnapshot previous) {
        byte[][] scalar = resolveSky(canonical);
        return RgbSnapshot.capture(minSection, revision, new byte[][][]{scalar, scalar, scalar}, scalar, previous);
    }
    public static RgbStorage fromSky(com.sumirelabs.pulsar.util.WorldHeightContext height,
                                     SWMRNibbleArray[] canonical, boolean[] empty) {
        RgbStorage result = new RgbStorage(height.getMinLightSection(), height.getTotalLightSections(),false);
        for (int c = 0; c < 3; c++) {
            result.empty[c] = empty == null ? null : empty.clone();
        }
        for (int s = 0; s < result.channels[0].length; s++) {
            SWMRNibbleArray source = canonical[s];
            // Freeze only engine-owned storage. Vanilla wrappers must retain
            // their writable array identity and keep the independent-copy path.
            boolean share=source!=null && source.canShareVisibleData();
            byte[] bytes = source == null || source.isNullNibbleVisible() || source.isFullVisible()
                    ? null : share ? source.shareVisibleData() : source.getVisibleData();
            for (int c = 0; c < 3; c++) {
                SWMRNibbleArray copy;
                if (source == null || source.isNullNibbleVisible()) copy = new SWMRNibbleArray(null, true);
                else if (source.isFullVisible()) { copy = new SWMRNibbleArray(); copy.setFull(); copy.updateVisible(); }
                else {
                    copy = share ? SWMRNibbleArray.fromImmutable(bytes)
                            : SWMRNibbleArray.fromOwned(bytes==null?null:bytes.clone(),
                                    bytes==null?SWMRNibbleArray.INIT_STATE_UNINIT:SWMRNibbleArray.INIT_STATE_INIT);
                }
                result.channels[c][s] = copy;
            }
        }
        return result;
    }
    public RgbSnapshot skySnapshot(com.sumirelabs.pulsar.util.WorldHeightContext height, long revision,
                                   SWMRNibbleArray[] canonical, RgbSnapshot previous) {
        byte[][][] data = new byte[3][][];
        for (int c = 0; c < 3; c++) data[c] = resolveSky(channels[c]);
        return RgbSnapshot.capture(minSection, revision, data, resolveSky(canonical), previous);
    }
    private static byte[][] resolveSky(SWMRNibbleArray[] sky) {
        byte[][] result = new byte[sky.length][];
        SWMRNibbleArray above = null;
        byte[] extrusion = null;
        boolean resolved = false;
        // All 256 columns use the same first non-null section above. Resolve
        // that section once, then reuse its bottom-row extrusion across holes.
        for (int s = result.length - 1; s >= 0; s--) {
            SWMRNibbleArray nibble = sky[s];
            if (nibble != null && !nibble.isNullNibbleVisible()) {
                above = nibble;
                resolved = false;
                if (nibble.isFullVisible()) result[s] = FULL_SKY;
                else {
                    byte[] bytes = nibble.getVisibleData();
                    if (bytes != null) for (byte value : bytes) if (value != 0) { result[s] = bytes; break; }
                }
            } else {
                if (!resolved) { extrusion = extrudeBottom(above); resolved = true; }
                result[s] = extrusion;
            }
        }
        return result;
    }
    static byte[][] snapshotSky(SWMRNibbleArray[] sky) {
        return snapshotSky(sky,null);
    }
    static byte[][] snapshotSky(SWMRNibbleArray[] sky,byte[][] previous) {
        byte[][] result=new byte[sky.length][];
        boolean reuse=previous!=null && previous.length==sky.length;
        SWMRNibbleArray above=null;
        byte[] extrusion=null;
        boolean resolved=false;
        for(int s=result.length-1;s>=0;s--) {
            SWMRNibbleArray nibble=sky[s];
            if(nibble!=null && !nibble.isNullNibbleVisible()) {
                above=nibble;resolved=false;
                result[s]=nibble.isFullVisible()?FULL_SKY:RgbSnapshot.copyPlane(nibble.getVisibleData(),reuse?previous[s]:null);
            } else {
                // extrudeBottom returns fresh owned bytes or the shared full
                // constant; capture once for every contiguous run of holes.
                if(!resolved){
                    extrusion=extrudeBottom(above);resolved=true;
                    if(reuse && java.util.Arrays.equals(extrusion,previous[s]))extrusion=previous[s];
                }
                result[s]=extrusion;
            }
        }
        return result;
    }
    private static byte[] extrudeBottom(SWMRNibbleArray above) {
        if (above == null || above.isFullVisible()) return FULL_SKY;
        byte[] bytes = above.getVisibleData();
        if (bytes == null) return null;
        boolean nonzero = false, full = true;
        for (int i = 0; i < 128; i++) { nonzero |= bytes[i] != 0; full &= bytes[i] == (byte)0xFF; }
        if (!nonzero) return null;
        if (full) return FULL_SKY;
        byte[] extrusion = new byte[2048];
        for (int row = 0; row < 16; row++) System.arraycopy(bytes, 0, extrusion, row * 128, 128);
        return extrusion;
    }
    public RgbSnapshot snapshot(long revision, SWMRNibbleArray[] canonical) {
        return snapshot(revision, canonical, null);
    }
    public RgbSnapshot snapshot(long revision, SWMRNibbleArray[] canonical, RgbSnapshot previous) {
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
        return RgbSnapshot.capture(minSection, revision, data, scalar, previous);
    }
}
