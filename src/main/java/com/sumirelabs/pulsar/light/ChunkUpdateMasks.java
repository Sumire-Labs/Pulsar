package com.sumirelabs.pulsar.light;

/** Section replacements must not set the protocol's full-chunk lifecycle flag. */
public final class ChunkUpdateMasks {
    private ChunkUpdateMasks() {}
    public static int[] split(int requested,int fullMask) {
        int selected=requested & fullMask;
        if(selected==0) return new int[0];
        if(selected!=0xFFFF && selected!=fullMask) return new int[]{selected};
        int even=selected & 0x55555555,odd=selected & 0xAAAAAAAA;
        if(even==0 || odd==0) throw new IllegalArgumentException("Cannot split a full one-parity section mask");
        return new int[]{even,odd};
    }
}
