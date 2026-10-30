/* SPDX-License-Identifier: LGPL-3.0-only
 * Default source hues adapted from GTNewHorizons/SuperNova DefaultColors/LightColors,
 * commit 22d8559c3064d77d115e58523c58cde824f4e214 (https://github.com/GTNewHorizons/SuperNova).
 */
package com.sumirelabs.pulsar.api;

import com.sumirelabs.pulsar.rgb.RgbLight;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/** Static, state-specific RGB chromaticity. Register during initialization, before worlds load,
 * preferably in {@link RegisterLightPropertiesEvent}. Registration is per exact canonical
 * block state; the All methods cover every canonical state of a block.
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
    private BlockLightColors() {}
    /** Register a packed 0xRGB hue for one state. Black is not a hue. */
    public static void register(IBlockState state, int color) {
        PackedLightColor.checked(color);
        register(state, color >>> 8, color >>> 4 & 15, color & 15);
    }
    /** Register a packed 0xRGB absolute emission for one state, including black. */
    public static void registerEmission(IBlockState state, int color) {
        PackedLightColor.checked(color);
        registerEmission(state, color >>> 8, color >>> 4 & 15, color & 15);
    }
    /** Register a hue for all states. A later per-state registration can override it.
     * The registry cannot freeze partway through this operation.
     */
    public static synchronized void registerAll(Block block, int color) {
        requireMutableBlock(block);
        RgbLight.normalize(PackedLightColor.checked(color)); // Validate before any writes.
        for (IBlockState state : block.getBlockState().getValidStates()) register(state, color);
    }
    /** Register actual component strengths for all states, including black. */
    public static synchronized void registerEmissionAll(Block block, int color) {
        requireMutableBlock(block);
        PackedLightColor.checked(color);
        for (IBlockState state : block.getBlockState().getValidStates()) registerEmission(state, color);
    }
    /** Disable RGB emission for all states; VANILLA scalar emission is unchanged. */
    public static synchronized void disableAll(Block block) {
        requireMutableBlock(block);
        for (IBlockState state : block.getBlockState().getValidStates()) disable(state);
    }
    private static void requireMutableBlock(Block block) {
        if (frozen != null) throw new IllegalStateException("RGB colors are frozen after initialization");
        java.util.Objects.requireNonNull(block, "block");
        if (block == Blocks.AIR) throw new IllegalArgumentException("Air cannot be an RGB source");
    }
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
        var palette = com.sumirelabs.pulsar.rgb.RgbPaletteOverrides.current();
        if (palette.profile == null) {
            java.util.List<String> rows = new java.util.ArrayList<>();
            rows.add("gameplay:" + com.sumirelabs.pulsar.rgb.RgbSettings.profile());
            if (frozen == null) throw new IllegalStateException("RGB colors not initialized");
            Map<IBlockState, Integer> colors = new IdentityHashMap<>(frozen);
            colors.putAll(palette.values.colors());
            for (var entry : colors.entrySet()) rows.add(entry.getKey().toString() + "=" + entry.getValue());
            for (IBlockState state : frozenAbsolute) if (!palette.values.colors().containsKey(state)) rows.add("absolute:" + state);
            Map<IBlockState,Integer> filters = new IdentityHashMap<>(BlockLightFilters.frozenFilters());
            filters.putAll(palette.values.filters());
            for (var entry : filters.entrySet()) rows.add("filter:" + entry.getKey() + "=" + entry.getValue());
            for (IBlockState state : net.minecraft.block.Block.BLOCK_STATE_IDS) {
                int dynamic = RgbStateProperties.compute(state,palette) & RgbStateProperties.DYNAMIC;
                if (dynamic != 0) rows.add("dynamic:" + state + ":" + state.getBlock().getClass().getName() + "=" + dynamic);
            }
            java.util.Collections.sort(rows);
            try {
                byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(String.join("\n", rows).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                palette.profile = java.util.HexFormat.of().formatHex(digest);
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        }
        return palette.profile;
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
        return PackedLightColor.checked(value);
    }
    public static synchronized void defaults() {
        // SuperNova DefaultColors/LightColors, commit 22d8559c3064d77d115e58523c58cde824f4e214.
        // Keep Pulsar's contextual scalar peak; adopt hue without changing source strength.
        defaultHue(Blocks.TORCH, 0xDA8);
        defaultHue(Blocks.GLOWSTONE, 0xFD3);
        // Warmer lava hue; retain its contextual emission strength.
        defaultHue(Blocks.LAVA, 0xFCA);
        defaultHue(Blocks.FLOWING_LAVA, 0xFCA);
        defaultHue(Blocks.REDSTONE_TORCH, 0x611);
        defaultHue(Blocks.SEA_LANTERN, 0xAFF);
        defaultHue(Blocks.END_ROD, 0xFAF);
        defaultHue(Blocks.MAGMA, 0x510);
        defaultHue(Blocks.LIT_REDSTONE_ORE, 0x511);
        defaultHue(Blocks.FIRE, 0xEC0);
        defaultHue(Blocks.END_PORTAL, 0x7FB);
        defaultHue(Blocks.LIT_PUMPKIN, 0xFDB);
        defaultHue(Blocks.BREWING_STAND, 0x110);
        defaultHue(Blocks.ENDER_CHEST, 0x376);
        defaultHue(Blocks.DRAGON_EGG, 0x111);
        defaultHue(Blocks.LIT_REDSTONE_LAMP, 0xFCA);
        defaultHue(Blocks.BEACON, 0xCCC);
        defaultHue(Blocks.PORTAL, 0x72B);
        defaultHue(Blocks.BROWN_MUSHROOM, 0x110);
        defaultHue(Blocks.LIT_FURNACE, 0xDCA);
        defaultHue(Blocks.POWERED_COMPARATOR, 0x611);
        defaultHue(Blocks.POWERED_REPEATER, 0x611);
    }
    /** Internal lifecycle hook: optional mods have completed post-init by this point. */
    public static synchronized void modDefaults() {
        if (frozen != null) throw new IllegalStateException("RGB colors already frozen");
        com.sumirelabs.pulsar.compat.ModLightColors.registerDefaults((state, color) ->
                COLORS.putIfAbsent(state, RgbLight.normalize(color)));
    }
    private static void defaultHue(net.minecraft.block.Block block, int color) {
        int normalized = RgbLight.normalize(color);
        for (IBlockState state : block.getBlockState().getValidStates()) COLORS.putIfAbsent(state, normalized);
    }
}
