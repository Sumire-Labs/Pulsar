package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.light.engine.LightInfo;
import net.minecraft.block.state.IBlockState;
import net.minecraft.world.chunk.BlockStatePaletteHashMap;
import net.minecraft.world.chunk.BlockStatePaletteLinear;
import net.minecraft.world.chunk.IBlockStatePalette;

/** Local vanilla palettes have at most 256 entries, versus 4096 cells per section. */
public final class ContextualLightPaletteChecks {
    private ContextualLightPaletteChecks() {}

    public static int flags(final IBlockStatePalette palette) {
        final int capacity;
        if (palette != null && palette.getClass() == BlockStatePaletteLinear.class) {
            capacity = 16;
        } else if (palette != null && palette.getClass() == BlockStatePaletteHashMap.class) {
            capacity = 256;
        } else {
            // Registry palettes, replacements and subclasses keep the full scan.
            return ContextualLightPalette.CONSERVATIVE_FLAGS;
        }
        int flags = 0;
        for (int index = 0; index < capacity; index++) {
            final IBlockState state = palette.getBlockState(index);
            if (state == null) continue;
            final int info = LightInfo.of(state);
            if (LightInfo.emission(info) > 0 || (info & LightInfo.CONTEXT_EMISSION) != 0) {
                flags |= ContextualLightPalette.MAY_EMIT;
            }
            if ((info & LightInfo.CONTEXT_OPACITY) != 0) {
                flags |= ContextualLightPalette.CONTEXT_OPACITY;
            }
            if ((info & LightInfo.CONTEXT_EMISSION) != 0) {
                flags |= ContextualLightPalette.CONTEXT_EMISSION;
            }
            if (flags == ContextualLightPalette.CONSERVATIVE_FLAGS) break;
        }
        return flags;
    }

    public static boolean mayNeedSamples(final IBlockStatePalette palette) {
        return (flags(palette) & ContextualLightPalette.CONTEXT_MASK) != 0;
    }
}
