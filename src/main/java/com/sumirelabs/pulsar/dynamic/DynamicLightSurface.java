package com.sumirelabs.pulsar.dynamic;
/** Preserve fractional render light in the 8-bit lightmap coordinate; gameplay remains integer light. */
public final class DynamicLightSurface {

    private DynamicLightSurface() {}
    public static int lightmap(int original, float level) {
        int dynamic = Math.max(0, Math.min(240, Math.round(level * 16)));
        return original & 0xffff0000 | Math.max(original & 65535, dynamic);
    }

}
