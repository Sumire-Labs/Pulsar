package com.sumirelabs.pulsar.api;

import com.sumirelabs.pulsar.rgb.RgbLight;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.item.EnumDyeColor;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/** Static minimum absorption per RGB component; RGB_MAX also uses this for gameplay light.
 * Register during initialization, preferably in {@link RegisterLightPropertiesEvent}.
 * Registration is per exact canonical block state. Values 0..15 are combined with the existing
 * contextual, fluid and face absorption using max; filters never open an opaque face.
 * Zero means no additional absorption, and 15 stops that component completely.
 */
public final class BlockLightFilters {
    private static final Map<IBlockState, Integer> FILTERS = new IdentityHashMap<>();
    private static volatile Map<IBlockState, Integer> frozen;
    private BlockLightFilters() {}

    /** Register packed 0xRGB minimum absorption for one state, not transmittance. */
    public static void register(IBlockState state, int absorption) {
        PackedLightColor.checked(absorption);
        register(state, absorption >>> 8, absorption >>> 4 & 15, absorption & 15);
    }

    /** Register minimum absorption for every canonical state of a block.
     * Later per-state registrations override it; freezing cannot split the operation.
     */
    public static synchronized void registerAll(Block block, int absorption) {
        if (frozen != null) throw new IllegalStateException("RGB filters are frozen after initialization");
        java.util.Objects.requireNonNull(block, "block");
        if (block == Blocks.AIR) throw new IllegalArgumentException("Air cannot filter RGB light");
        PackedLightColor.checked(absorption);
        for (IBlockState state : block.getBlockState().getValidStates()) register(state, absorption);
    }

    public static synchronized void register(IBlockState state, int r, int g, int b) {
        if (frozen != null) throw new IllegalStateException("RGB filters are frozen after initialization");
        if (state.getBlock() == Blocks.AIR) throw new IllegalArgumentException("Air cannot filter RGB light");
        FILTERS.put(state, RgbLight.pack(r, g, b));
    }

    public static int absorption(IBlockState state, int channel, int scalarAbsorption) {
        // A component filter cannot exceed 15 or open an already opaque face.
        if (scalarAbsorption >= 15) return scalarAbsorption;
        int properties = RgbStateProperties.of(state);
        if ((properties & RgbStateProperties.FILTER_PRESENT) == 0) return scalarAbsorption;
        int filter = RgbStateProperties.filter(properties);
        return Math.max(scalarAbsorption, RgbLight.channel(filter, channel));
    }
    public static boolean mayFilterSky(IBlockState state) {
        int properties = RgbStateProperties.of(state);
        return (properties & RgbStateProperties.FILTER_FROZEN) == 0
                || (properties & RgbStateProperties.DYNAMIC_FILTER) != 0 || RgbStateProperties.filter(properties) != 0;
    }
    static Map<IBlockState, Integer> frozenFilters() { return frozen; }
    public static synchronized void interfaceDefaults() {
        if (frozen != null) throw new IllegalStateException("RGB filters already frozen");
        for (IBlockState state : net.minecraft.block.Block.BLOCK_STATE_IDS)
            if (state.getBlock() instanceof ColoredTranslucency source
                    && !(source instanceof PositionalColoredTranslucency))
                FILTERS.putIfAbsent(state, 0xFFF ^ BlockLightColors.checkedColor(source.getColoredTransmittance(state)));
    }

    public static synchronized void defaults() {
        for (EnumDyeColor dye : EnumDyeColor.values()) {
            int filter = 0xFFF ^ com.sumirelabs.pulsar.rgb.RgbDefaultPalette.glassTransmittance(dye);
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
