package com.sumirelabs.pulsar.api;

import com.sumirelabs.pulsar.rgb.RgbLight;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/** Static, state-specific RGB chromaticity. Register during initialization, before worlds load.
 * The largest component is normalized to the block's existing contextual scalar emission.
 * Black is not a light chromaticity and is rejected; use scalar emission zero to disable a source.
 * Unregistered states emit white. This API does not alter gameplay brightness.
 */
public final class BlockLightColors {
    private static final Map<IBlockState, Integer> COLORS = new IdentityHashMap<>();
    private static volatile Map<IBlockState, Integer> frozen;
    private static String profile;
    private BlockLightColors() {}
    public static synchronized void register(IBlockState state, int r, int g, int b) {
        if (frozen != null) throw new IllegalStateException("RGB colors are frozen after initialization");
        if (state.getBlock() == Blocks.AIR) throw new IllegalArgumentException("Air cannot be an RGB source");
        COLORS.put(state, RgbLight.normalize(RgbLight.pack(r, g, b)));
    }
    public static synchronized void freeze() {
        BlockLightFilters.freeze();
        if (frozen == null) frozen = Collections.unmodifiableMap(new IdentityHashMap<>(COLORS));
    }
    public static String profile() {
        if (profile == null) {
            java.util.List<String> rows = new java.util.ArrayList<>();
            Map<IBlockState, Integer> colors = frozen;
            if (colors == null) throw new IllegalStateException("RGB colors not initialized");
            for (var entry : colors.entrySet()) rows.add(entry.getKey().toString() + "=" + entry.getValue());
            BlockLightFilters.appendProfile(rows);
            java.util.Collections.sort(rows);
            try {
                byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(String.join("\n", rows).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                profile = java.util.HexFormat.of().formatHex(digest);
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        }
        return profile;
    }
    public static int emission(IBlockState state, int scalar) {
        Map<IBlockState, Integer> colors = frozen;
        Integer color = colors == null ? null : colors.get(state);
        return RgbLight.scale(color == null ? 0xFFF : color, scalar);
    }
    /** Resolve only the component needed by a propagation lane. */
    public static int emission(IBlockState state, int scalar, int channel) {
        if (scalar == 0) return 0;
        Map<IBlockState, Integer> colors = frozen;
        Integer color = colors == null ? null : colors.get(state);
        return color == null ? scalar : RgbLight.scaleChannel(color, scalar, channel);
    }
    public static synchronized void defaults() {
        for (IBlockState s : Blocks.TORCH.getBlockState().getValidStates()) COLORS.putIfAbsent(s, 0xFA6);
        for (IBlockState s : Blocks.GLOWSTONE.getBlockState().getValidStates()) COLORS.putIfAbsent(s, 0xFD9);
        for (IBlockState s : Blocks.LAVA.getBlockState().getValidStates()) COLORS.putIfAbsent(s, 0xF50);
        for (IBlockState s : Blocks.FLOWING_LAVA.getBlockState().getValidStates()) COLORS.putIfAbsent(s, 0xF50);
        for (IBlockState s : Blocks.REDSTONE_TORCH.getBlockState().getValidStates()) COLORS.putIfAbsent(s, 0xF10);
        for (IBlockState s : Blocks.SEA_LANTERN.getBlockState().getValidStates()) COLORS.putIfAbsent(s, 0x9DF);
    }
}
