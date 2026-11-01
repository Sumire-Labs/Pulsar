package com.sumirelabs.pulsar.dynamic;

import com.sumirelabs.pulsar.api.*;
import com.sumirelabs.pulsar.compat.FluidLightBridge;
import com.sumirelabs.pulsar.light.engine.LightInfo;
import com.sumirelabs.pulsar.rgb.RgbLightAccess;
import com.sumirelabs.pulsar.rgb.RgbSettings;
import com.sumirelabs.pulsar.util.WorldUtil;
import net.minecraft.block.state.IBlockState;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import java.util.Arrays;

/** Samples mod callbacks on the world thread; workers receive only primitive arrays. */
final class DynamicLightGeometry {
    record Snapshot(int x, int y, int z, int radius, int emission, short[] absorption, byte[] exits) {
        DynamicLightField propagate() { return DynamicLightField.propagate(x, y, z, radius, emission, absorption, exits); }
    }
    static int properties(World world, BlockPos pos, IBlockState state) {
        int properties = RgbStateProperties.of(state);
        if ((properties & RgbStateProperties.DYNAMIC_SOURCE) != 0) {
            int color = ((PositionalColoredLightSource) state.getBlock()).getColoredLightEmission(world, pos, state);
            if (color < 0 || color > 0xFFF) throw new IllegalArgumentException("Invalid contextual emission");
            properties = properties & ~0xFFF | color & 0xFFF;
        }
        if ((properties & RgbStateProperties.DYNAMIC_FILTER) != 0) {
            int transmittance = ((PositionalColoredTranslucency) state.getBlock()).getColoredTransmittance(world, pos, state);
            if (transmittance < 0 || transmittance > 0xFFF) throw new IllegalArgumentException("Invalid contextual transmittance");
            properties = properties & ~(0xFFF << 12) | (0xFFF ^ (transmittance & 0xFFF)) << 12;
        }
        return properties;
    }
    static int itemEmission(World world, BlockPos pos, ItemStack stack, boolean rgb) {
        try { return heldState(world, pos, stack, rgb); }
        catch (RuntimeException unsupported) { return 0; }
    }
    private static int heldState(World world, BlockPos pos, ItemStack stack, boolean rgb) {
        if (stack.isEmpty()) return 0;
        IBlockState state;
        if (stack.getItem() instanceof ItemBlock item)
            state = item.getBlock().getStateFromMeta(item.getMetadata(stack.getMetadata()));
        else if (stack.getItem() == net.minecraft.init.Items.LAVA_BUCKET)
            state = net.minecraft.init.Blocks.LAVA.getDefaultState();
        else return 0;
        return stateEmission(world, pos, state, rgb);
    }
    static int fireEmission(World world, BlockPos pos, boolean rgb) {
        return stateEmission(world, pos, net.minecraft.init.Blocks.FIRE.getDefaultState(), rgb);
    }
    private static int stateEmission(World world, BlockPos pos, IBlockState state, boolean rgb) {
        int scalar = Math.max(0, Math.min(15, state.getLightValue(world, pos)));
        int color = rgb ? RgbLightAccess.effectiveEmission(properties(world, pos, state), scalar,
                RgbSettings.projectsGameplayLight()) : scalar * 0x111;
        return color | scalar << 12;
    }
    static Snapshot capture(World world, BlockPos source, int emission, boolean rgb) {
        int radius = Math.max(DynamicLightField.peak(emission), emission >>> 12 & 15) - 1;
        radius = Math.max(0, radius);
        int side = radius * 2 + 1, size = DynamicLightField.cube(radius);
        short[] costs = new short[size * 6]; Arrays.fill(costs, (short) 0xFFFF);
        byte[] exits = new byte[size]; Arrays.fill(exits, (byte) 63);
        var height = WorldUtil.getHeightContext(world);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dy = -radius; dy <= radius; dy++) for (int dz = -radius; dz <= radius; dz++)
            for (int dx = -radius; dx <= radius; dx++) {
                if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > radius) continue;
                int x = source.getX() + dx, y = source.getY() + dy, z = source.getZ() + dz;
                if (y < height.getMinBlockY() || y > height.getMaxBlockY()) continue;
                var chunk = world.getChunkProvider().getLoadedChunk(x >> 4, z >> 4);
                if (chunk == null || chunk instanceof net.minecraft.world.chunk.EmptyChunk) continue;
                pos.setPos(x, y, z);
                IBlockState state = chunk.getBlockState(pos), fluid = FluidLightBridge.stateAt(chunk, x, y, z);
                int info = LightInfo.sampleContextual(LightInfo.of(state), state, world, pos, x, y, z);
                int fluidInfo = fluid == null ? 0 : LightInfo.sampleContextual(LightInfo.of(fluid), fluid, world, pos, x, y, z);
                int filter = rgb ? RgbStateProperties.filter(properties(world, pos, state)) : 0;
                if (rgb && fluid != null) filter = DynamicLightField.merge(filter, RgbStateProperties.filter(properties(world, pos, fluid)));
                int index = dx + radius + side * (dz + radius + side * (dy + radius));
                exits[index] = 0;
                for (int dir = 0; dir < 6; dir++) {
                    int scalar = LightInfo.absorption(info, state, dir);
                    if (fluid != null) scalar = Math.max(scalar, LightInfo.absorption(fluidInfo, fluid, dir));
                    // Closed faces also stop outgoing light from a partially transparent cell.
                    if (scalar >= 15) exits[index] |= (byte) (1 << dir);
                    costs[index * 6 + dir] = (short) DynamicLightField.merge(scalar * 0x1111, filter);
                }
            }
        return new Snapshot(source.getX(), source.getY(), source.getZ(), radius, emission, costs, exits);
    }
}
