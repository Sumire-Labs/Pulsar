/*
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.sumirelabs.pulsar.light.engine;

import java.util.*;

import com.sumirelabs.pulsar.api.BlockLightColors;
import com.sumirelabs.pulsar.api.BlockLightFilters;
import com.sumirelabs.pulsar.light.SWMRNibbleArray;
import com.sumirelabs.pulsar.util.WorldHeightContext;

import net.minecraft.block.Block;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import static com.sumirelabs.pulsar.light.engine.ReferenceLightSolver.*;

/** Real scalar, packed and component BFS kernels compared with a separate specification. */
public final class ReferenceLightProbe {

    private static final Map<Kind, IBlockState> STATES = new EnumMap<>(Kind.class);
    public static void main(String[] args) throws Exception {
        Bootstrap.register();
        var mods = net.minecraftforge.fml.common.Loader.class.getDeclaredField("namedMods");
        mods.setAccessible(true);
        if (mods.get(net.minecraftforge.fml.common.Loader.instance()) == null)
            mods.set(net.minecraftforge.fml.common.Loader.instance(), new HashMap<>());
        FaceOcclusion.registerDefaults();
        BlockLightColors.defaults();
        BlockLightFilters.defaults();
        STATES.put(Kind.AIR, Blocks.AIR.getDefaultState());
        STATES.put(Kind.STONE, Blocks.STONE.getDefaultState());
        STATES.put(Kind.GLASS, Blocks.GLASS.getDefaultState());
        STATES.put(Kind.WATER, Blocks.WATER.getDefaultState());
        STATES.put(Kind.RED_GLASS, Blocks.STAINED_GLASS.getStateFromMeta(EnumDyeColor.RED.getMetadata()));
        STATES.put(Kind.BLUE_GLASS, Blocks.STAINED_GLASS.getStateFromMeta(EnumDyeColor.BLUE.getMetadata()));
        STATES.put(Kind.TOP_SLAB,
            Blocks.WOODEN_SLAB.getDefaultState().withProperty(BlockSlab.HALF, BlockSlab.EnumBlockHalf.TOP));
        STATES.put(Kind.BOTTOM_SLAB,
            Blocks.WOODEN_SLAB.getDefaultState().withProperty(BlockSlab.HALF, BlockSlab.EnumBlockHalf.BOTTOM));
        for (Kind kind : List.of(Kind.STRONG, Kind.WEAK)) {
            int level = kind == Kind.STRONG ? 15 : 6;
            var state = new Block(Material.ROCK).setLightOpacity(0).setLightLevel(level / 15f).getDefaultState();
            STATES.put(kind, state);
            BlockLightColors.registerEmission(state, kind == Kind.STRONG ? 0xF24 : 0x161);
        }
        BlockLightColors.freeze();
        int comparisons = 0;
        for (int minSection : new int[]{0, -2})
            for (int seed : new int[]{91, 48392})
                for (int kind = 0; kind < 10; kind++) {
                    Fixture f = new Fixture(kind, minSection, seed);
                    f.flood();
                    f.compare("initial");
                    comparisons++;
                    int[][] edits = {{15, 8, 15}, {16, 8, 15}, {14, 8, 15}, {18, 8, 15}, {16, 9, 15}, {15, 8, 15},
                        {16, 8, 15}, {14, 8, 15}};
                    Kind[] values = {Kind.STRONG, Kind.RED_GLASS, Kind.AIR, Kind.WEAK, Kind.STONE, Kind.AIR,
                        Kind.BLUE_GLASS, Kind.STRONG};
                    for (int i = 0; i < edits.length; i++) {
                        int[] pos = edits[i];
                        f.change(pos[0], pos[1], pos[2], values[i]);
                        f.compare("edit=" + i);
                        comparisons++;
                    }
                }
        System.out.println("REFERENCE_LIGHT_PASS comparisons=" + comparisons + " cellsPerComparison=" + SIZE);
    }
    static final class Fixture {

        final PulsarEngine engine;
        final Kind[] cells = new Kind[SIZE];
        final int kind, bottom, origin;
        final boolean sky, scalar, packed;
        Fixture(int kind, int section, int seed) throws Exception {
            this.kind = kind;
            bottom = section * 16;
            origin = section < 0 ? -16 : 0;
            sky = kind >= 5;
            scalar = kind == 0 || kind == 5;
            packed = kind == 1 || kind == 6;
            var h = WorldHeightContext.contiguous(section, section);
            var u = Class.forName("sun.misc.Unsafe");
            var field = u.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            World w = (World) u.getMethod("allocateInstance", Class.class).invoke(field.get(null), WorldServer.class);
            engine = switch (kind) {
                case 0 -> new ScalarBlockEngine(w, h);
                case 1 -> new RgbPackedBlockChannelEngine(w, h, new HashSet<>());
                case 2, 3, 4 -> new RgbChannelEngine(w, h, kind - 2, new HashSet<>());
                case 5 -> new ScalarSkyEngine(w, h);
                case 6 -> new RgbPackedSkyChannelEngine(w, h, new HashSet<>());
                default -> new RgbSkyChannelEngine(w, h, kind - 7, new HashSet<>());
            };
            engine.setupEncodeOffset(15 + origin, bottom + 8, 15 + origin);
            for (int cx = 0; cx < 2; cx++)
                for (int cz = 0; cz < 2; cz++) {
                    int wx = cx + (origin >> 4), wz = cz + (origin >> 4);
                    engine.setChunkSectionInCache(wx, section, wz, new ExtendedBlockStorage(bottom, false));
                    if (packed)
                        for (int c = 0; c < 3; c++)
                            ((PackedRgbChannelEngine) engine).rgb.replacePlane(c,
                                wx + 5 * wz + 25 * section + engine.chunkSectionIndexOffset, new SWMRNibbleArray());
                    else
                        engine.setNibbleInCache(wx, section, wz, new SWMRNibbleArray());
                }
            Random random = new Random(seed);
            Kind[] scatter = {Kind.STONE, Kind.WATER, Kind.GLASS, Kind.RED_GLASS, Kind.BLUE_GLASS, Kind.TOP_SLAB,
                Kind.BOTTOM_SLAB};
            for (int y = 0; y < HEIGHT; y++)
                for (int z = 0; z < WIDTH; z++)
                    for (int x = 0; x < WIDTH; x++) {
                        Kind cell = x == 0 || x == 31 || z == 0 || z == 31 || y == 0 || y == 15
                            ? Kind.STONE
                            : random.nextInt(7) == 0 ? scatter[random.nextInt(scatter.length)] : Kind.AIR;
                        put(x, y, z, cell);
                    }
            put(14, 8, 15, Kind.STRONG);
            put(18, 8, 15, Kind.WEAK);
            put(15, 8, 15, Kind.TOP_SLAB);
            put(16, 8, 15, Kind.BOTTOM_SLAB);
        }
        void put(int x, int y, int z, Kind value) {
            cells[index(x, y, z)] = value;
            engine.getChunkSection((x + origin) >> 4, bottom >> 4, (z + origin) >> 4).set(x & 15, y, z & 15,
                STATES.get(value));
        }
        int encode(int[] values) {
            return packed ? values[0] << 16 | values[1] << 8 | values[2] : values[scalar ? 0 : kind - (sky ? 7 : 2)];
        }
        int source(Kind cell) {
            int[] values = new int[scalar ? 1 : 3];
            for (int c = 0; c < values.length; c++)
                values[c] = emission(cell, c, scalar, sky);
            return encode(values);
        }
        void seed(int x, int y, int z, int level, boolean decrease) {
            long entry = PulsarEngine.encodeCoords(x + origin, z + origin, bottom + y, engine.coordinateOffset)
                | engine.encodeQueueLevel(level) | 63L << engine.getDirectionShift();
            if (decrease)
                engine.appendToDecreaseQueue(entry);
            else
                engine.appendToIncreaseQueue(entry);
        }
        void flood() {
            for (int y = 1; y < 15; y++)
                for (int z = 1; z < 31; z++)
                    for (int x = 1; x < 31; x++) {
                        int light = source(cells[index(x, y, z)]);
                        if (light == 0)
                            continue;
                        engine.setLightLevel(x + origin, bottom + y, z + origin, light);
                        seed(x, y, z, light, false);
                    }
            engine.performLightIncrease();
        }
        void change(int x, int y, int z, Kind value) {
            Kind old = cells[index(x, y, z)];
            int previous = engine.getLightLevel(x + origin, bottom + y, z + origin);
            put(x, y, z, value);
            engine.resetTaskState();
            if (sky && (old == Kind.STRONG || old == Kind.WEAK || value == Kind.STRONG || value == Kind.WEAK)) {
                int level = source(value);
                engine.setLightLevel(x + origin, bottom + y, z + origin, level);
                if (level != 0)
                    seed(x, y, z, level, false);
                seed(x, y, z, previous, true);
            } else
                engine.checkBlock(x + origin, bottom + y, z + origin);
            engine.performLightDecrease();
        }
        void compare(String phase) {
            if (engine.wasQueueOverflowed())
                throw new AssertionError("Unexpected overflow");
            int[][] expected = ReferenceLightSolver.solve(cells, scalar, sky);
            for (int y = 0; y < HEIGHT; y++)
                for (int z = 0; z < WIDTH; z++)
                    for (int x = 0; x < WIDTH; x++) {
                        int target = encode(new int[]{expected[0][index(x, y, z)],
                            scalar ? 0 : expected[1][index(x, y, z)], scalar ? 0 : expected[2][index(x, y, z)]});
                        int actual = engine.getLightLevel(x + origin, bottom + y, z + origin);
                        if (target != actual)
                            throw new AssertionError("kind=" + kind + " y0=" + bottom + " " + phase + " at=" + x + ","
                                + y + "," + z + " expected=" + Integer.toHexString(target) + " actual="
                                + Integer.toHexString(actual));
                    }
        }

    }

}
