package com.sumirelabs.pulsar.rgb;

import com.sumirelabs.pulsar.api.BlockLightFilters;
import net.minecraft.util.BitArray;
import net.minecraft.world.chunk.*;

public final class RgbFilterPaletteChecks {
    private RgbFilterPaletteChecks() {}
    public static boolean hasFilters(IBlockStatePalette palette, BitArray storage) {
        int capacity;
        if (palette != null && palette.getClass() == BlockStatePaletteLinear.class) capacity = 16;
        else if (palette != null && palette.getClass() == BlockStatePaletteHashMap.class) capacity = 256;
        else return true;
        boolean present = false;
        for (int i = 0; i < capacity; i++) {
            var state = palette.getBlockState(i);
            if (state != null && BlockLightFilters.mayFilterSky(state)) { present = true; break; }
        }
        if (!present) return false;
        if (storage == null || storage.size() != 4096) return true;
        // Palette entries can outlive their last cell. Check occupancy so removal
        // of the last filter does not keep the expensive path active forever.
        for (int i = 0; i < 4096; i++) {
            var state = palette.getBlockState(storage.getAt(i));
            if (state == null || BlockLightFilters.mayFilterSky(state)) return true;
        }
        return false;
    }
}
