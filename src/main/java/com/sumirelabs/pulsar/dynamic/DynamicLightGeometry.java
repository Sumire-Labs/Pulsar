package com.sumirelabs.pulsar.dynamic;

import java.util.Arrays;

import com.sumirelabs.pulsar.api.*;
import com.sumirelabs.pulsar.compat.FluidLightBridge;
import com.sumirelabs.pulsar.light.engine.LightInfo;
import com.sumirelabs.pulsar.rgb.RgbLightAccess;
import com.sumirelabs.pulsar.rgb.RgbSettings;
import com.sumirelabs.pulsar.util.WorldUtil;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** Samples mod callbacks on the world thread; workers receive only primitive arrays. */
final class DynamicLightGeometry {

    private static final net.minecraft.util.ResourceLocation ARCHITECTURE_SHAPE = new net.minecraft.util.ResourceLocation(
        "architecturecraft", "shape");

    record Snapshot(int x, int y, int z, int radius, int emission, short[] absorption, byte[] exits) {

        DynamicLightField propagate() {
            return DynamicLightField.propagate(x, y, z, radius, emission, absorption, exits);
        }

    }
    static int properties(World world, BlockPos pos, IBlockState state) {
        int properties = RgbStateProperties.of(state);
        if ((properties & RgbStateProperties.DYNAMIC_SOURCE) != 0) {
            int color = ((PositionalColoredLightSource) state.getBlock()).getColoredLightEmission(world, pos, state);
            if (color < 0 || color > 0xFFF)
                throw new IllegalArgumentException("Invalid contextual emission");
            properties = properties & ~0xFFF | color & 0xFFF;
        }
        if ((properties & RgbStateProperties.DYNAMIC_FILTER) != 0) {
            int transmittance = ((PositionalColoredTranslucency) state.getBlock()).getColoredTransmittance(world, pos,
                state);
            if (transmittance < 0 || transmittance > 0xFFF)
                throw new IllegalArgumentException("Invalid contextual transmittance");
            properties = properties & ~(0xFFF << 12) | (0xFFF ^ (transmittance & 0xFFF)) << 12;
        }
        return properties;
    }
    static int itemEmission(World world, BlockPos pos, ItemStack stack, boolean rgb) {
        try {
            return heldState(world, pos, stack, rgb);
        } catch (RuntimeException unsupported) {
            return 0;
        }
    }
    private static int heldState(World world, BlockPos pos, ItemStack stack, boolean rgb) {
        if (stack.isEmpty())
            return 0;
        IBlockState state;
        if (stack.getItem() instanceof ItemBlock item)
            state = item.getBlock().getStateFromMeta(item.getMetadata(stack.getMetadata()));
        else if (stack.getItem() == net.minecraft.init.Items.LAVA_BUCKET)
            state = net.minecraft.init.Blocks.LAVA.getDefaultState();
        else
            return 0;
        var options = com.sumirelabs.pulsar.config.PulsarConfig.dynamicLights;
        if (options.extinguishTorchesInWater && (state.getBlock() instanceof net.minecraft.block.BlockTorch
            || options.waterSensitiveItems.length > 0 && java.util.Arrays.asList(options.waterSensitiveItems)
                .contains(String.valueOf(stack.getItem().getRegistryName())))
            && world.getBlockState(pos).getMaterial() == net.minecraft.block.material.Material.WATER)
            return 0;
        if (rgb && ARCHITECTURE_SHAPE.equals(state.getBlock().getRegistryName())
            && (RgbStateProperties.of(state) & RgbStateProperties.DYNAMIC_SOURCE) != 0)
            return shapeItemEmission(stack, state);
        return stateEmission(world, pos, state, rgb);
    }

    private static int shapeItemEmission(ItemStack stack, IBlockState state) {
        // Renovated stores LIGHT in damage and the base material in the stack.
        // Secondary glazing belongs to the placed tile, not the picked-up shape.
        int level = state.getLightValue();
        if (level == 0)
            return 0;
        int hue = 0xFFF;
        var tag = stack.getTagCompound();
        if (tag != null && tag.hasKey("BaseName", 8)) {
            Block material = Block.getBlockFromName(tag.getString("BaseName"));
            if (material != null && material != Blocks.AIR)
                hue = RgbStateProperties
                    .color(RgbStateProperties.of(material.getStateFromMeta(tag.getInteger("BaseData"))));
        }
        // Match Renovated's placed source, which rounds each channel down.
        return level << 12 | ((hue >> 8) * level / 15) << 8 | ((hue >> 4 & 15) * level / 15) << 4
            | (hue & 15) * level / 15;
    }
    static int fireEmission(World world, BlockPos pos, boolean rgb) {
        return stateEmission(world, pos, net.minecraft.init.Blocks.FIRE.getDefaultState(), rgb);
    }
    static int stateEmission(World world, BlockPos pos, IBlockState state, boolean rgb) {
        int scalar = Math.max(0, Math.min(15, state.getLightValue(world, pos)));
        int color = rgb
            ? RgbLightAccess.effectiveEmission(properties(world, pos, state), scalar,
                RgbSettings.projectsGameplayLight())
            : scalar * 0x111;
        return color | scalar << 12;
    }
    static Snapshot capture(World world, BlockPos source, int emission, boolean rgb) {
        int radius = DynamicLightField.CAPTURE_RADIUS;
        int side = radius * 2 + 1, size = DynamicLightField.cube(radius);
        short[] costs = new short[size * 6];
        Arrays.fill(costs, (short) 0xFFFF);
        byte[] exits = new byte[size];
        Arrays.fill(exits, (byte) 63);
        var height = WorldUtil.getHeightContext(world);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minChunkX = (source.getX() - radius) >> 4, minChunkZ = (source.getZ() - radius) >> 4;
        int width = ((source.getX() + radius) >> 4) - minChunkX + 1;
        int depth = ((source.getZ() + radius) >> 4) - minChunkZ + 1;
        var chunks = new net.minecraft.world.chunk.Chunk[width * depth];
        for (int cz = 0; cz < depth; cz++)
            for (int cx = 0; cx < width; cx++)
                chunks[cx + width * cz] = world.getChunkProvider().getLoadedChunk(minChunkX + cx, minChunkZ + cz);
        for (int dy = -radius; dy <= radius; dy++)
            for (int dz = -radius; dz <= radius; dz++)
                for (int dx = -radius; dx <= radius; dx++) {
                    if (dx * dx + dy * dy + dz * dz > DynamicLightField.VISUAL_RANGE * DynamicLightField.VISUAL_RANGE)
                        continue;
                    int x = source.getX() + dx, y = source.getY() + dy, z = source.getZ() + dz;
                    if (y < height.getMinBlockY() || y > height.getMaxBlockY())
                        continue;
                    var chunk = chunks[(x >> 4) - minChunkX + width * ((z >> 4) - minChunkZ)];
                    // Server providers return null for unloaded chunks. EmptyChunk is client-only.
                    if (chunk == null)
                        continue;
                    pos.setPos(x, y, z);
                    IBlockState state = chunk.getBlockState(pos), fluid = FluidLightBridge.stateAt(chunk, x, y, z);
                    int info = LightInfo.sampleContextual(LightInfo.of(state), state, world, pos, x, y, z);
                    int fluidInfo = fluid == null
                        ? 0
                        : LightInfo.sampleContextual(LightInfo.of(fluid), fluid, world, pos, x, y, z);
                    int filter = rgb ? RgbStateProperties.filter(properties(world, pos, state)) : 0;
                    if (rgb && fluid != null)
                        filter = DynamicLightField.merge(filter,
                            RgbStateProperties.filter(properties(world, pos, fluid)));
                    int index = dx + radius + side * (dz + radius + side * (dy + radius));
                    exits[index] = 0;
                    for (int dir = 0; dir < 6; dir++) {
                        int scalar = LightInfo.absorption(info, state, dir);
                        if (fluid != null)
                            scalar = Math.max(scalar, LightInfo.absorption(fluidInfo, fluid, dir));
                        // Closed faces also stop outgoing light from a partially transparent cell.
                        if (scalar >= 15)
                            exits[index] |= (byte) (1 << dir);
                        costs[index * 6 + dir] = (short) DynamicLightField.merge(scalar * 0x1111, filter);
                    }
                }
        return new Snapshot(source.getX(), source.getY(), source.getZ(), radius, emission, costs, exits);
    }

}
