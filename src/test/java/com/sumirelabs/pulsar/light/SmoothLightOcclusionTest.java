package com.sumirelabs.pulsar.light;

import net.minecraft.block.BlockSlab;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BlockModelRenderer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SmoothLightOcclusionTest {
    @BeforeAll static void bootstrap() { Bootstrap.register(); }

    @Test void hidingLightCannotBrightenTheOtherChannel() {
        assertEquals(2 << 4, SmoothLightOcclusion.limitLight((15 << 20) | (2 << 4), 14 << 4));
        assertEquals(3 << 20, SmoothLightOcclusion.limitLight((3 << 20) | (15 << 4), 14 << 20));
    }

    @Test void realVanillaAoLeaksFromAnIsolatedDiagonal() throws Exception {
        for (EnumFacing normal : EnumFacing.VALUES) {
            for (int light : new int[]{15 << 4, 15 << 20, (15 << 4) | (15 << 20)}) {
                Fixture world = fixture(normal, light);
                assertEquals(0, world.rawLight(world.center.offset(normal)));
                int[] baseline = render(world, normal);
                assertTrue(Arrays.stream(baseline).anyMatch(value -> value != 0), normal.toString());

                // The fixture is dark in storage despite the positive vertex light.
                assertEquals(light, world.rawLight(world.source));
                assertEquals(0, world.rawLight(world.center.offset(normal)));
            }
        }
    }

    @Test void anOpenRouteKeepsTheNativeLightGradient() throws Exception {
        for (EnumFacing normal : EnumFacing.VALUES) {
            Fixture world = fixture(normal, 15 << 4);
            BlockPos edge = world.center.offset(normal).offset(first(normal));
            world.states.put(edge, Blocks.AIR.getDefaultState());
            world.lights.put(edge, 14 << 4);
            world.lights.put(world.center.offset(normal), 13 << 4);
            int[] baseline = render(world, normal);

            assertTrue(Arrays.stream(baseline).allMatch(value -> value > 0));
        }
    }

    @Test void slabOcclusionUsesTheHalfAtTheRenderedCorner() {
        for (EnumFacing normal : new EnumFacing[]{EnumFacing.UP, EnumFacing.DOWN}) {
            Fixture world = fixture(normal, 15 << 20);
            BlockPos base = world.center.offset(normal);
            BlockPos edge = base.offset(first(normal));
            for (BlockSlab.EnumBlockHalf half : BlockSlab.EnumBlockHalf.values()) {
                world.states.put(edge, Blocks.WOODEN_SLAB.getDefaultState().withProperty(BlockSlab.HALF, half));
                boolean filled = half == (normal == EnumFacing.UP ? BlockSlab.EnumBlockHalf.BOTTOM : BlockSlab.EnumBlockHalf.TOP);
                int corner = normal == EnumFacing.UP ? 0 : 3;
                assertEquals(filled, (SmoothLightOcclusion.hiddenSamples(world, base.getX(), base.getY(), base.getZ(),
                        normal, corner, 0, new BlockPos.MutableBlockPos()) & 4) != 0);
            }
        }
    }

    @Test void transparentMediaDoNotSealADiagonal() {
        for (IBlockState state : new IBlockState[]{Blocks.GLASS.getDefaultState(), Blocks.WATER.getDefaultState(),
                Blocks.LEAVES.getDefaultState(), Blocks.AIR.getDefaultState()}) {
            Fixture world = fixture(EnumFacing.UP, 15 << 4);
            BlockPos base = world.center.up();
            world.states.put(base.east(), state);
            assertEquals(0, SmoothLightOcclusion.hiddenSamples(world, base.getX(), base.getY(), base.getZ(),
                    EnumFacing.UP, 0, 0, new BlockPos.MutableBlockPos()) & 4, state.toString());
        }
    }

    @Test void stairCornersRespectBothHalvesAllFacingsAndInnerOuterShapes() {
        for (EnumFacing facing : EnumFacing.HORIZONTALS) {
            for (BlockStairs.EnumHalf half : BlockStairs.EnumHalf.values()) {
                for (BlockStairs.EnumShape shape : BlockStairs.EnumShape.values()) {
                    IBlockState state = Blocks.OAK_STAIRS.getDefaultState().withProperty(BlockStairs.FACING, facing)
                            .withProperty(BlockStairs.HALF, half).withProperty(BlockStairs.SHAPE, shape);
                    int count = 0;
                    for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++) {
                        if (SmoothLightOcclusion.stairCorner(state, x, y, z)) count++;
                    }
                    assertEquals(switch (shape) {
                        case STRAIGHT -> 6;
                        case OUTER_LEFT, OUTER_RIGHT -> 5;
                        case INNER_LEFT, INNER_RIGHT -> 7;
                    }, count, state.toString());
                    int stepY = half == BlockStairs.EnumHalf.TOP ? 0 : 1;
                    int frontX = facing.getXOffset() > 0 ? 1 : 0;
                    int frontZ = facing.getZOffset() > 0 ? 1 : 0;
                    if (shape == BlockStairs.EnumShape.STRAIGHT) {
                        assertTrue(SmoothLightOcclusion.stairCorner(state, frontX, stepY, frontZ));
                        assertFalse(SmoothLightOcclusion.stairCorner(state, 1 - frontX, stepY, 1 - frontZ));
                    }
                }
            }
        }
    }

    private static EnumFacing first(EnumFacing normal) {
        return normal.getAxis() == EnumFacing.Axis.X ? EnumFacing.UP : EnumFacing.EAST;
    }

    private static EnumFacing second(EnumFacing normal) {
        return normal.getAxis() == EnumFacing.Axis.Z ? EnumFacing.UP : EnumFacing.SOUTH;
    }

    private static Fixture fixture(EnumFacing normal, int light) {
        Fixture world = new Fixture(normal);
        BlockPos base = world.center.offset(normal);
        world.states.put(base, Blocks.AIR.getDefaultState());
        world.source = base.offset(first(normal)).offset(second(normal));
        world.states.put(world.source, Blocks.GLOWSTONE.getDefaultState());
        world.lights.put(world.source, light);
        // Vanilla checks this extra row of air rather than the two solid blocks
        // separating the dark cell from the completely enclosed light source.
        world.states.put(base.offset(first(normal)).offset(normal), Blocks.AIR.getDefaultState());
        world.states.put(base.offset(second(normal)).offset(normal), Blocks.AIR.getDefaultState());
        return world;
    }

    private static int[] render(Fixture world, EnumFacing normal) throws Exception {
        BlockModelRenderer renderer = new BlockModelRenderer(null);
        Class<?> type = Class.forName("net.minecraft.client.renderer.BlockModelRenderer$AmbientOcclusionFace");
        Constructor<?> constructor = type.getDeclaredConstructor(BlockModelRenderer.class);
        constructor.setAccessible(true);
        Object ao = constructor.newInstance(renderer);
        var update = type.getDeclaredMethod("updateVertexBrightness", IBlockAccess.class, IBlockState.class,
                BlockPos.class, EnumFacing.class, float[].class, BitSet.class);
        update.setAccessible(true);
        BitSet flags = new BitSet();
        flags.set(0);
        update.invoke(ao, world, Blocks.STONE.getDefaultState(), world.center, normal, new float[12], flags);
        var brightness = type.getDeclaredField("vertexBrightness");
        brightness.setAccessible(true);
        return ((int[]) brightness.get(ao)).clone();
    }

    private static final class Fixture implements IBlockAccess {
        final BlockPos center = new BlockPos(-31, 64, 47);
        final Map<BlockPos, IBlockState> states = new HashMap<>();
        final Map<BlockPos, Integer> lights = new HashMap<>();
        final EnumFacing normal;
        BlockPos source;


        Fixture(EnumFacing normal) { this.normal = normal; }
        int rawLight(BlockPos pos) { return lights.getOrDefault(pos, 0); }
        public int getCombinedLight(BlockPos pos, int emission) {
            int light = rawLight(pos);
            return (light & 0xffff0000) | Math.max(light & 0xffff, emission << 4);
        }
        public IBlockState getBlockState(BlockPos pos) { return states.getOrDefault(pos, Blocks.STONE.getDefaultState()); }
        public TileEntity getTileEntity(BlockPos pos) { return null; }
        public boolean isAirBlock(BlockPos pos) { return getBlockState(pos).getBlock() == Blocks.AIR; }
        public Biome getBiome(BlockPos pos) { return null; }
        public int getStrongPower(BlockPos pos, EnumFacing side) { return 0; }
        public WorldType getWorldType() { return WorldType.DEFAULT; }
        public boolean isSideSolid(BlockPos pos, EnumFacing side, boolean fallback) { return getBlockState(pos).isSideSolid(this, pos, side); }
    }
}
