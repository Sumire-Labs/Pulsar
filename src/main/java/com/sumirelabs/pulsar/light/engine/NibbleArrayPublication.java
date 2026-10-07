package com.sumirelabs.pulsar.light.engine;

import com.sumirelabs.pulsar.light.SWMRNibbleArray;
import com.sumirelabs.pulsar.util.WorldHeightContext;

/** Nibble-array reference helpers shared by full-light publication and skylight init. */
final class NibbleArrayPublication {
    private NibbleArrayPublication() {}

    static int lightIndex(final WorldHeightContext height, final int sectionY) {
        return height.getLightSectionIndex(sectionY);
    }

    /** Merge cache references into a newly allocated full-relight baseline. */
    static void mergeFullLight(final SWMRNibbleArray[] baseline, final SWMRNibbleArray[] cache) {
        if (baseline.length != cache.length) throw new IllegalArgumentException("Light array sizes differ");
        for (int i = 0; i < baseline.length; i++) {
            if (cache[i] != null) baseline[i] = cache[i];
            if (baseline[i] == null) baseline[i] = new SWMRNibbleArray(null, true);
        }
    }

    static SWMRNibbleArray takeRemovedNibble(final SWMRNibbleArray[] removed,
                                              final int index, final boolean initRemoved) {
        SWMRNibbleArray result = removed[index];
        removed[index] = null;
        if (result == null && initRemoved) result = new SWMRNibbleArray(null, true);
        return result;
    }

}
