package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.light.engine.LightInfo;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraftforge.fml.common.Loader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContextualLightManagerCaptureTest {
    private static java.lang.reflect.Field namedMods;
    private static Object previousMods;

    @BeforeAll static void bootstrap() throws Exception {
        Bootstrap.register();
        // This test uses real chunks without starting Forge's mod discovery.
        namedMods = Loader.class.getDeclaredField("namedMods");
        namedMods.setAccessible(true);
        previousMods = namedMods.get(Loader.instance());
        if (previousMods == null) namedMods.set(Loader.instance(), java.util.Map.of());
    }

    @AfterAll static void restoreLoader() throws Exception {
        namedMods.set(Loader.instance(), previousMods);
    }

    private static final class ContextBlock extends Block {
        int calls;
        ContextBlock() { super(Material.ROCK); }
        @Override public int getLightValue(IBlockState state, IBlockAccess access, BlockPos pos) {
            calls++;
            int value = pos.getX() & 15;
            ((BlockPos.MutableBlockPos) pos).setPos(999, 999, 999);
            return value;
        }
    }

    private static final class TestChunk extends Chunk {
        TestChunk(int x, int z) { super(null, x, z); }
        @Override public IBlockState getBlockState(int x, int y, int z) {
            // Use real section storage without World's debug-world lookup.
            ExtendedBlockStorage[] sections = this.getBlockStorageArray();
            int index = y >> 4;
            if (index < 0 || index >= sections.length || sections[index] == null) {
                return Blocks.AIR.getDefaultState();
            }
            return sections[index].get(x & 15, y & 15, z & 15);
        }
    }

    @Test void chunkLoadCapturesEachContextualCellBeforeWorkersReadIt() {
        final var block = new ContextBlock();
        final IBlockState state = block.getDefaultState();
        final Chunk chunk = new TestChunk(-2, 3);
        final var section = new ExtendedBlockStorage(64, true);
        chunk.getBlockStorageArray()[4] = section;
        for (int x = 0; x < 16; x++) section.set(x, 3, 7, state);
        final var manager = new ContextualLightManager(null, WorldHeightContext.VANILLA);
        manager.load(chunk);
        assertEquals(16, block.calls);
        final int staticInfo = LightInfo.of(state);
        for (int x = 0; x < 16; x++) {
            assertEquals(x, LightInfo.emission(manager.read(staticInfo, state, -32 + x, 67, 55)));
        }
        assertEquals(16, block.calls, "Reads must consume samples without invoking block callbacks");
        assertFalse(manager.hasPending(-2, 3));
        manager.unload(-2, 3);
        assertEquals(staticInfo, manager.read(staticInfo, state, -32, 67, 55));
    }
}
