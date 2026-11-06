package com.sumirelabs.pulsar.light.engine;

import org.junit.jupiter.api.Test;

import com.sumirelabs.pulsar.light.SWMRNibbleArray;
import com.sumirelabs.pulsar.util.WorldHeightContext;

import net.minecraft.block.BlockSlab;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import static org.junit.jupiter.api.Assertions.*;

class AdjacentSlabLightTest {

    @Test
    void oppositeHalvesSealTheSharedBoundaryButEqualHalvesStayOpen() throws Exception {
        Bootstrap.register();
        FaceOcclusion.registerDefaults();
        for (int kind = 0; kind < 10; kind++)
            for (var half : BlockSlab.EnumBlockHalf.values())
                for (boolean same : new boolean[]{false, true}) {
                    var w = world();
                    var h = WorldHeightContext.contiguous(0, 0);
                    PulsarEngine engine = switch (kind) {
                        case 0 -> new ScalarBlockEngine(w, h);
                        case 1 -> new ScalarSkyEngine(w, h);
                        case 2, 3, 4 -> new RgbChannelEngine(w, h, kind - 2, new java.util.HashSet<>());
                        case 5 -> new RgbPackedBlockChannelEngine(w, h, new java.util.HashSet<>());
                        case 6 -> new RgbPackedSkyChannelEngine(w, h, new java.util.HashSet<>());
                        default -> new RgbSkyChannelEngine(w, h, kind - 7, new java.util.HashSet<>());
                    };
                    engine.setupEncodeOffset(7, 8, 7);
                    var section = new ExtendedBlockStorage(0, true);
                    engine.setChunkSectionInCache(0, 0, 0, section);
                    boolean packed = engine instanceof PackedRgbChannelEngine;
                    if (packed)
                        for (int c = 0; c < 3; c++)
                            ((PackedRgbChannelEngine) engine).rgb.replacePlane(c, engine.chunkSectionIndexOffset,
                                new SWMRNibbleArray());
                    else
                        engine.setNibbleInCache(0, 0, 0, new SWMRNibbleArray());
                    // A narrow sealed corridor leaves only the slab/slab edge as a path.
                    for (int x = 0; x < 16; x++)
                        for (int y = 0; y < 16; y++)
                            for (int z = 0; z < 16; z++)
                                section.set(x, y, z, Blocks.STONE.getDefaultState());
                    section.set(6, 8, 8, Blocks.GLOWSTONE.getDefaultState());
                    section.set(7, 8, 8, Blocks.WOODEN_SLAB.getDefaultState().withProperty(BlockSlab.HALF, half));
                    section.set(8, 8, 8,
                        Blocks.WOODEN_SLAB.getDefaultState().withProperty(BlockSlab.HALF,
                            same
                                ? half
                                : half == BlockSlab.EnumBlockHalf.TOP
                                    ? BlockSlab.EnumBlockHalf.BOTTOM
                                    : BlockSlab.EnumBlockHalf.TOP));
                    section.set(9, 8, 8, Blocks.AIR.getDefaultState());
                    int source = packed ? 0x0F0F0F : 15;
                    engine.setLightLevel(6, 8, 8, source);
                    long seed = packed
                        ? ((PackedRgbChannelEngine) engine).entry(6, 8, 8, source, PulsarEngine.ALL_DIRECTIONS_BITSET,
                            0)
                        : PulsarEngine.encodeCoords(6, 8, 8, engine.coordinateOffset) | engine.encodeQueueLevel(source)
                            | ((long) PulsarEngine.ALL_DIRECTIONS_BITSET << PulsarEngine.DIRECTION_SHIFT);
                    engine.appendToIncreaseQueue(seed);
                    engine.performLightIncrease();
                    assertEquals(same ? (packed ? 0x0C0C0C : 12) : 0, engine.getLightLevel(9, 8, 8),
                        "kind=" + kind + " half=" + half + " same=" + same);
                    if (same) {
                        section.set(8, 8, 8, Blocks.WOODEN_SLAB.getDefaultState().withProperty(BlockSlab.HALF,
                            half == BlockSlab.EnumBlockHalf.TOP
                                ? BlockSlab.EnumBlockHalf.BOTTOM
                                : BlockSlab.EnumBlockHalf.TOP));
                        engine.resetTaskState();
                        engine.checkBlock(8, 8, 8);
                        engine.performLightDecrease();
                        assertEquals(0, engine.getLightLevel(9, 8, 8), "Closing boundary kind=" + kind);
                        section.set(8, 8, 8, Blocks.WOODEN_SLAB.getDefaultState().withProperty(BlockSlab.HALF, half));
                        engine.resetTaskState();
                        engine.checkBlock(8, 8, 8);
                        engine.performLightDecrease();
                        assertEquals(packed ? 0x0C0C0C : 12, engine.getLightLevel(9, 8, 8),
                            "Reopening boundary kind=" + kind);
                    }
                }
    }
    private static World world() throws Exception {
        var mods = net.minecraftforge.fml.common.Loader.class.getDeclaredField("namedMods");
        mods.setAccessible(true);
        if (mods.get(net.minecraftforge.fml.common.Loader.instance()) == null)
            mods.set(net.minecraftforge.fml.common.Loader.instance(), new java.util.HashMap<>());
        var type = Class.forName("sun.misc.Unsafe");
        var field = type.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (World) type.getMethod("allocateInstance", Class.class).invoke(field.get(null), WorldServer.class);
    }

}
