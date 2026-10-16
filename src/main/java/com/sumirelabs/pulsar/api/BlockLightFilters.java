package com.sumirelabs.pulsar.api;

import com.sumirelabs.pulsar.rgb.RgbLight;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.item.EnumDyeColor;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/** Static minimum absorption per RGB component, independent of gameplay light.
 * Register during initialization. Values 0..15 are combined with the existing
 * contextual, fluid and face absorption using max; filters never open an opaque face.
 * Zero means no additional absorption, and 15 stops that component completely.
 */
public final class BlockLightFilters {
    private static final Map<IBlockState, Integer> FILTERS = new IdentityHashMap<>();
    private static volatile Map<IBlockState, Integer> frozen;
    private BlockLightFilters() {}

    public static synchronized void register(IBlockState state, int r, int g, int b) {
        if (frozen != null) throw new IllegalStateException("RGB filters are frozen after initialization");
        if (state.getBlock() == Blocks.AIR) throw new IllegalArgumentException("Air cannot filter RGB light");
        FILTERS.put(state, RgbLight.pack(r, g, b));
    }

    public static int absorption(IBlockState state, int channel, int scalarAbsorption) {
        Map<IBlockState, Integer> filters = frozen;
        Integer filter = filters == null ? null : filters.get(state);
        return filter == null ? scalarAbsorption : Math.max(scalarAbsorption, RgbLight.channel(filter, channel));
    }
    public static boolean mayFilterSky(IBlockState state) {
        Map<IBlockState, Integer> filters = frozen;
        if (filters == null) return true;
        Integer filter = filters.get(state);
        return filter != null && filter != 0;
    }

    public static synchronized void defaults() {
        for (EnumDyeColor dye : EnumDyeColor.values()) {
            float[] color = dye.getColorComponentValues();
            int filter = RgbLight.pack(Math.round(15 * (1 - color[0])),
                    Math.round(15 * (1 - color[1])), Math.round(15 * (1 - color[2])));
            for (IBlockState state : Blocks.STAINED_GLASS.getBlockState().getValidStates())
                if (state.getBlock().getMetaFromState(state) == dye.getMetadata()) FILTERS.putIfAbsent(state, filter);
            for (IBlockState state : Blocks.STAINED_GLASS_PANE.getBlockState().getValidStates())
                if (state.getBlock().getMetaFromState(state) == dye.getMetadata()) FILTERS.putIfAbsent(state, filter);
        }
    }

    public static synchronized void freeze() {
        if (frozen == null) frozen = Collections.unmodifiableMap(new IdentityHashMap<>(FILTERS));
    }

    static void appendProfile(java.util.List<String> rows) {
        Map<IBlockState, Integer> filters = frozen;
        if (filters == null) throw new IllegalStateException("RGB filters not initialized");
        for (var entry : filters.entrySet()) rows.add("filter:" + entry.getKey() + "=" + entry.getValue());
    }
}
