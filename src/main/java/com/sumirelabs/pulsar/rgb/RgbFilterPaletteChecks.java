package com.sumirelabs.pulsar.rgb;

import com.sumirelabs.pulsar.api.BlockLightFilters;
import net.minecraft.util.BitArray;
import net.minecraft.world.chunk.*;

public final class RgbFilterPaletteChecks {
    public static final int FILTER = 1, ABSOLUTE_EMISSION = 2, DYNAMIC = 4, ALL = 7;
    private RgbFilterPaletteChecks() {}
    public static boolean hasFilters(IBlockStatePalette palette, BitArray storage) {
        return (flags(palette, storage) & FILTER) != 0;
    }
    private static int flags(net.minecraft.block.state.IBlockState state) {
        if (state == null) return ALL;
        int properties = com.sumirelabs.pulsar.api.RgbStateProperties.of(state);
        return (BlockLightFilters.mayFilterSky(state) ? FILTER : 0)
                | (com.sumirelabs.pulsar.api.RgbStateProperties.mayEmitAbsolute(state) ? ABSOLUTE_EMISSION : 0)
                | ((properties & com.sumirelabs.pulsar.api.RgbStateProperties.DYNAMIC) != 0 ? DYNAMIC : 0);
    }
    public static int flags(IBlockStatePalette palette, BitArray storage) {
        int capacity;
        if (palette != null && palette.getClass() == BlockStatePaletteLinear.class) capacity = 16;
        else if (palette != null && palette.getClass() == BlockStatePaletteHashMap.class) capacity = 256;
        else return ALL;
        int candidates = 0;
        for (int i = 0; i < capacity; i++) {
            var state = palette.getBlockState(i);
            if (state != null) candidates |= flags(state);
        }
        if (candidates == 0) return 0;
        if (storage == null || storage.size() != 4096) return candidates;
        // Palette entries can outlive their last cell. Check occupancy so removal
        // of the last filter does not keep the expensive path active forever.
        int occupied = 0;
        for (int i = 0; i < 4096; i++) {
            var state = palette.getBlockState(storage.getAt(i));
            occupied |= flags(state);
            if (occupied == candidates || occupied == ALL) break;
        }
        return occupied;
    }
}
