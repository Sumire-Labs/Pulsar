package com.sumirelabs.pulsar.light.engine;

import com.sumirelabs.pulsar.light.SWMRNibbleArray;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.Map;

/** Uses the production skylight BFS with actual section/nibble storage. */
public final class StairRoomLight extends ScalarSkyEngine {
    public StairRoomLight(Map<BlockPos, IBlockState> blocks) throws Exception {
        super(emptyWorld(), WorldHeightContext.contiguous(0, 0));
        setupEncodeOffset(7, 8, 7);
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            for (int y = minLightSection; y <= maxLightSection; y++) {
                setChunkSectionInCache(x, y, z, new ExtendedBlockStorage(y << 4, true));
                setNibbleInCache(x, y, z, new SWMRNibbleArray());
            }
        }
        for (var block : blocks.entrySet()) {
            BlockPos pos = block.getKey();
            getChunkSection(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4)
                    .set(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, block.getValue());
        }
        for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
            if (x >= 4 && x <= 10 && y >= 4 && y <= 10 && z >= 4 && z <= 10) continue;
            if (getBlockState(x, y, z).getBlock() != Blocks.AIR) continue;
            setLightLevel(x, y, z, 15);
            appendToIncreaseQueue(encodeCoords(x, z, y, coordinateOffset) | encodeQueueLevel(15)
                    | ((long) ALL_DIRECTIONS_BITSET << DIRECTION_SHIFT));
        }
        performLightIncrease();
    }

    public int sky(BlockPos pos) { return getLightLevel(pos.getX(), pos.getY(), pos.getZ()); }

    private static World emptyWorld() throws Exception {
        var mods = net.minecraftforge.fml.common.Loader.class.getDeclaredField("namedMods");
        mods.setAccessible(true);
        if (mods.get(net.minecraftforge.fml.common.Loader.instance()) == null) {
            mods.set(net.minecraftforge.fml.common.Loader.instance(), new java.util.HashMap<>());
        }
        Class<?> type = Class.forName("sun.misc.Unsafe");
        var field = type.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (World) type.getMethod("allocateInstance", Class.class).invoke(field.get(null), WorldServer.class);
    }
}
