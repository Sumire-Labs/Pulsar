package com.sumirelabs.pulsar.api;

import com.sumirelabs.pulsar.rgb.RgbLight;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/** Static, state-specific RGB chromaticity. Register during initialization, before worlds load.
 * The largest component is normalized to the block's existing contextual scalar emission.
 * Black chromaticity is rejected by register; use disable to suppress RGB emission.
 * Unregistered states emit white. RGB_MAX gameplay brightness follows emitted RGB;
 * VANILLA retains independent scalar gameplay brightness.
 */
public final class BlockLightColors {
    private static final Map<IBlockState, Integer> COLORS = new IdentityHashMap<>();
    private static volatile Map<IBlockState, Integer> frozen;
    private static final java.util.Set<IBlockState> ABSOLUTE = Collections.newSetFromMap(new IdentityHashMap<>());
    private static volatile java.util.Set<IBlockState> frozenAbsolute;
    private static String profile;
    private BlockLightColors() {}
    public static synchronized void register(IBlockState state, int r, int g, int b) {
        if (frozen != null) throw new IllegalStateException("RGB colors are frozen after initialization");
        if (state.getBlock() == Blocks.AIR) throw new IllegalArgumentException("Air cannot be an RGB source");
        COLORS.put(state, RgbLight.normalize(RgbLight.pack(r, g, b)));
        ABSOLUTE.remove(state);
    }
    /** Register actual per-component emission rather than a normalized chromaticity.
     * RGB_MAX uses these levels directly; VANILLA caps the peak to existing scalar emission.
     */
    public static synchronized void registerEmission(IBlockState state, int r, int g, int b) {
        if (frozen != null) throw new IllegalStateException("RGB colors are frozen after initialization");
        if (state.getBlock() == Blocks.AIR) throw new IllegalArgumentException("Air cannot be an RGB source");
        COLORS.put(state, RgbLight.pack(r, g, b)); ABSOLUTE.add(state);
    }
    /** Disable RGB emission; VANILLA retains independent scalar gameplay brightness. */
    public static synchronized void disable(IBlockState state) {
        if (frozen != null) throw new IllegalStateException("RGB colors are frozen after initialization");
        if (state.getBlock() == Blocks.AIR) throw new IllegalArgumentException("Air cannot be an RGB source");
        COLORS.put(state, 0);
        ABSOLUTE.remove(state);
    }
    public static synchronized void freeze() {
        BlockLightFilters.freeze();
        if (frozen == null) {
            var absolute = Collections.newSetFromMap(new IdentityHashMap<IBlockState,Boolean>());
            absolute.addAll(ABSOLUTE); frozenAbsolute = Collections.unmodifiableSet(absolute);
            frozen = Collections.unmodifiableMap(new IdentityHashMap<>(COLORS));
        }
    }
    public static String profile() {
        if (profile == null) {
            java.util.List<String> rows = new java.util.ArrayList<>();
            rows.add("gameplay:" + com.sumirelabs.pulsar.rgb.RgbSettings.profile());
            Map<IBlockState, Integer> colors = frozen;
            if (colors == null) throw new IllegalStateException("RGB colors not initialized");
            for (var entry : colors.entrySet()) rows.add(entry.getKey().toString() + "=" + entry.getValue());
            for (IBlockState state : frozenAbsolute) rows.add("absolute:" + state);
            BlockLightFilters.appendProfile(rows);
            for (IBlockState state : net.minecraft.block.Block.BLOCK_STATE_IDS) {
                int dynamic = RgbStateProperties.of(state) & RgbStateProperties.DYNAMIC;
                if (dynamic != 0) rows.add("dynamic:" + state + ":" + state.getBlock().getClass().getName() + "=" + dynamic);
            }
            java.util.Collections.sort(rows);
            try {
                byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(String.join("\n", rows).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                profile = java.util.HexFormat.of().formatHex(digest);
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        }
        return profile;
    }
    public static int emission(IBlockState state, int scalar) {
        return com.sumirelabs.pulsar.rgb.RgbLightAccess.effectiveEmission(RgbStateProperties.of(state), scalar,
                com.sumirelabs.pulsar.rgb.RgbSettings.projectsGameplayLight());
    }
    /** Resolve only the component needed by a propagation lane. */
    public static int emission(IBlockState state, int scalar, int channel) {
        return RgbLight.channel(emission(state, scalar), channel);
    }
    static Map<IBlockState, Integer> frozenColors() { return frozen; }
    static boolean absolute(IBlockState state) { var values = frozenAbsolute; return values != null && values.contains(state); }
    /** Initialization-only state callbacks; explicit registrations retain precedence. */
    public static synchronized void interfaceDefaults() {
        if (frozen != null) throw new IllegalStateException("RGB colors already frozen");
        for (IBlockState state : net.minecraft.block.Block.BLOCK_STATE_IDS)
            if (state.getBlock() instanceof ColoredLightSource source
                    && !(source instanceof PositionalColoredLightSource) && !COLORS.containsKey(state)) {
                int value = checkedColor(source.getColoredLightEmission(state));
                COLORS.put(state, value); ABSOLUTE.add(state);
            }
    }
    public static int checkedColor(int value) {
        if ((value & ~0xFFF) != 0) throw new IllegalArgumentException("Colored API must return 0xRGB with four bits per component");
        return value;
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
