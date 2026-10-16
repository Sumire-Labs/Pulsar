package com.sumirelabs.pulsar.rgb;

/** RGB-only section classification. Unknown containers conservatively require color work. */
public interface RgbFilterPalette {
    record Cache(long revision, net.minecraft.world.chunk.IBlockStatePalette palette,
                 net.minecraft.util.BitArray storage, boolean filtered) {}
    default boolean pulsar$mayFilterSky() { return true; }
    default void pulsar$invalidateSkyFilterCache() {}
}
