package com.sumirelabs.pulsar.rgb;

import com.sumirelabs.pulsar.api.RgbStateProperties;
import com.sumirelabs.pulsar.light.engine.LightInfo;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** RGB cell properties; contextual scalar and color callbacks remain behind their owner snapshots. */
public final class RgbLightAccess {
    private RgbLightAccess() {}
    public static int packedEmission(World world, IBlockState block, IBlockState fluid, int x, int y, int z,
                                     BlockPos.MutableBlockPos pos) {
        int color = packedSource(world, block, x, y, z, pos);
        return fluid == null ? color : PackedRgbLight.packedMax(color, packedSource(world, fluid, x, y, z, pos));
    }
    private static int packedSource(World world, IBlockState state, int x, int y, int z, BlockPos.MutableBlockPos pos) {
        int scalar = LightInfo.emission(LightInfo.resolveContextual(LightInfo.of(state), state, world, pos, x, y, z));
        int properties = RgbContextualLightManager.properties(world, state, x, y, z);
        return PackedRgbLight.fromCompact(effectiveEmission(properties, scalar, RgbSettings.projectsGameplayLight()));
    }
    public static int packedAbsorption(World world, IBlockState block, IBlockState fluid, int x, int y, int z, int scalar) {
        if (scalar >= 15) return PackedRgbLight.ALL_CHANNELS;
        int filter = PackedRgbLight.fromCompact(RgbStateProperties.filter(
                RgbContextualLightManager.properties(world, block, x, y, z)));
        if (fluid != null) filter = PackedRgbLight.packedMax(filter, PackedRgbLight.fromCompact(RgbStateProperties.filter(
                RgbContextualLightManager.properties(world, fluid, x, y, z))));
        return PackedRgbLight.packedMax(PackedRgbLight.pack(scalar, scalar, scalar), filter);
    }
    public static int emission(World world, IBlockState block, IBlockState fluid, int x, int y, int z,
                               int channel, BlockPos.MutableBlockPos pos) {
        int result = source(world, block, x, y, z, channel, pos);
        return fluid == null ? result : Math.max(result, source(world, fluid, x, y, z, channel, pos));
    }
    private static int source(World world, IBlockState state, int x, int y, int z,
                              int channel, BlockPos.MutableBlockPos pos) {
        int info = LightInfo.resolveContextual(LightInfo.of(state), state, world, pos, x, y, z);
        int scalar = LightInfo.emission(info);
        int properties = RgbContextualLightManager.properties(world, state, x, y, z);
        return RgbLight.channel(effectiveEmission(properties, scalar, RgbSettings.projectsGameplayLight()), channel);
    }
    public static int effectiveEmission(int properties, int scalar, boolean projected) {
        int color = RgbStateProperties.color(properties);
        if ((properties & RgbStateProperties.SOURCE_ABSOLUTE) == 0) return RgbLight.scale(color, scalar);
        int peak = Math.max(color >>> 8 & 15, Math.max(color >>> 4 & 15, color & 15));
        if (projected || peak <= scalar) return color;
        return scalar == 0 ? 0 : RgbLight.scale(RgbLight.normalize(color), scalar);
    }
    public static int absorption(World world, IBlockState block, IBlockState fluid, int x, int y, int z,
                                 int channel, int scalar) {
        if (scalar >= 15) return scalar;
        int filter = RgbLight.channel(RgbStateProperties.filter(
                RgbContextualLightManager.properties(world, block, x, y, z)), channel);
        if (fluid != null) filter = Math.max(filter, RgbLight.channel(RgbStateProperties.filter(
                RgbContextualLightManager.properties(world, fluid, x, y, z)), channel));
        return Math.max(scalar, filter);
    }
}
