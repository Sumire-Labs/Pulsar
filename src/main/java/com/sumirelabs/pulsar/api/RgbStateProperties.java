package com.sumirelabs.pulsar.api;

import com.sumirelabs.pulsar.rgb.RgbCachedState;
import net.minecraft.block.state.IBlockState;
import java.util.Map;

/** Internal packed registry snapshot used by RGB-only state caches. */
public final class RgbStateProperties {
    public static final int COLOR_FROZEN = 1 << 24;
    public static final int FILTER_FROZEN = 1 << 25;
    public static final int FILTER_PRESENT = 1 << 26;
    public static final int DYNAMIC_SOURCE = 1 << 27;
    public static final int DYNAMIC_FILTER = 1 << 28;
    public static final int DYNAMIC = DYNAMIC_SOURCE | DYNAMIC_FILTER;
    public static final int SOURCE_ABSOLUTE = 1 << 29;
    private static volatile Boolean dynamicStates;
    private RgbStateProperties() {}
    public static int of(IBlockState state) {
        return state instanceof RgbCachedState cached ? cached.pulsar$rgbProperties() : compute(state);
    }
    public static int compute(IBlockState state) {
        // Capture each immutable registry once. A concurrent freeze cannot mark
        // an earlier default lookup as final and poison the per-state cache.
        Map<IBlockState, Integer> colors = BlockLightColors.frozenColors();
        Map<IBlockState, Integer> filters = BlockLightFilters.frozenFilters();
        Integer color = colors == null ? null : colors.get(state);
        Integer filter = filters == null ? null : filters.get(state);
        return pack(color == null ? 0xFFF : color, filter == null ? 0 : filter,
                colors != null, filters != null) | (filter != null ? FILTER_PRESENT : 0)
                | (color == null && state.getBlock() instanceof PositionalColoredLightSource ? DYNAMIC_SOURCE : 0)
                | (BlockLightColors.absolute(state) || color == null && state.getBlock() instanceof PositionalColoredLightSource ? SOURCE_ABSOLUTE : 0)
                | (filter == null && state.getBlock() instanceof PositionalColoredTranslucency ? DYNAMIC_FILTER : 0);
    }
    public static int pack(int color, int filter, boolean colorsFrozen, boolean filtersFrozen) {
        return color | filter << 12 | (colorsFrozen ? COLOR_FROZEN : 0) | (filtersFrozen ? FILTER_FROZEN : 0);
    }
    public static int color(int properties) { return properties & 0xFFF; }
    public static int filter(int properties) { return properties >>> 12 & 0xFFF; }
    public static boolean cacheable(int properties) {
        return (properties & (COLOR_FROZEN | FILTER_FROZEN)) == (COLOR_FROZEN | FILTER_FROZEN);
    }
    public static boolean mayEmitAbsolute(IBlockState state) {
        int properties = of(state);
        return (properties & COLOR_FROZEN) == 0 || (properties & DYNAMIC_SOURCE) != 0
                || (properties & SOURCE_ABSOLUTE) != 0 && color(properties) != 0;
    }
    public static boolean hasDynamicStates() {
        Boolean cached = dynamicStates;
        if (cached != null) return cached;
        if (BlockLightColors.frozenColors() == null || BlockLightFilters.frozenFilters() == null) return true;
        for (IBlockState state : net.minecraft.block.Block.BLOCK_STATE_IDS)
            if ((of(state) & DYNAMIC) != 0) { dynamicStates = true; return true; }
        dynamicStates = false;
        return false;
    }
}
