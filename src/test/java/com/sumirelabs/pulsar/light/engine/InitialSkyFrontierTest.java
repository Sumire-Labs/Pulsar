package com.sumirelabs.pulsar.light.engine;

import com.sumirelabs.pulsar.util.WorldHeightContext;
import net.minecraft.init.Bootstrap;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class InitialSkyFrontierTest {
    @BeforeAll static void bootstrap() { Bootstrap.register(); }

    private static ScalarSkyEngine engine(int chunkX, int chunkZ) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        var field = unsafe.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        World world = (World) unsafe.getMethod("allocateInstance", Class.class)
                .invoke(field.get(null), WorldServer.class);
        var engine = new ScalarSkyEngine(world, WorldHeightContext.contiguous(-4, 19));
        engine.setupEncodeOffset(chunkX * 16 + 7, 128, chunkZ * 16 + 7);
        return engine;
    }

    private static int[] stops(ScalarSkyEngine engine) throws Exception {
        var field = ScalarSkyEngine.class.getDeclaredField("initialSkyStops");
        field.setAccessible(true);
        return (int[]) field.get(engine);
    }

    private static void trim(ScalarSkyEngine engine, int first) throws Exception {
        var method = ScalarSkyEngine.class.getDeclaredMethod("trimInitialSkySeeds", int.class);
        method.setAccessible(true);
        method.invoke(engine, first);
    }

    private static long seed(ScalarSkyEngine engine, int x, int y, int z) {
        return PulsarEngine.encodeCoords(x, z, y, engine.coordinateOffset)
                | engine.encodeQueueLevel(15)
                | (PulsarEngine.AxisDirection.POSITIVE_Y.everythingButThisDirection << PulsarEngine.DIRECTION_SHIFT);
    }

    @Test void flatSurfaceKeepsFloorAndChunkBoundaryPropagation() throws Exception {
        var engine = engine(-3, 2);
        Arrays.fill(stops(engine), 3);
        for (int z = 32; z < 48; z++) for (int x = -48; x < -32; x++) {
            for (int y = 16; y >= 4; y--) engine.appendToIncreaseQueue(seed(engine, x, y, z));
        }
        assertEquals(3328, engine.increaseQueueInitialLength);
        trim(engine, 0);
        assertEquals(976, engine.increaseQueueInitialLength);
        int directions = 0;
        for (int i = 0; i < engine.increaseQueueInitialLength; i++) {
            directions += Integer.bitCount((int) (engine.increaseQueue[i] >>> PulsarEngine.DIRECTION_SHIFT) & 63);
        }
        assertEquals(1088, directions);
    }

    @Test void removedDirectionsOnlyTargetAlreadyFullInteriorCells() throws Exception {
        var random = new Random(0x534b594c);
        for (int chunkX : new int[]{-301, 0, 24000}) {
            var engine = engine(chunkX, -chunkX);
            int[] stops = stops(engine);
            for (int i = 0; i < stops.length; i++) stops[i] = random.nextInt(384) - 65;
            // Includes negative Y and the extended world's upper boundary.
            for (int i = 0; i < 3000; i++) {
                int x = random.nextInt(16), z = random.nextInt(16), y = random.nextInt(384) - 64;
                if (y <= stops[x | (z << 4)]) continue;
                long original = seed(engine, chunkX * 16 + x, y, -chunkX * 16 + z);
                engine.increaseQueue[0] = original;
                engine.increaseQueueInitialLength = 1;
                trim(engine, 0);
                int retained = engine.increaseQueueInitialLength == 0 ? 0
                        : (int) (engine.increaseQueue[0] >>> PulsarEngine.DIRECTION_SHIFT) & 63;
                if (retained != 0) assertEquals(original & ~(63L << PulsarEngine.DIRECTION_SHIFT),
                        engine.increaseQueue[0] & ~(63L << PulsarEngine.DIRECTION_SHIFT));
                for (var direction : PulsarEngine.AXIS_DIRECTIONS) {
                    if (direction == PulsarEngine.AxisDirection.POSITIVE_Y) continue;
                    int dx = x + direction.x, dz = z + direction.z, dy = y + direction.y;
                    boolean destinationFull = dx >= 0 && dx < 16 && dz >= 0 && dz < 16
                            && dy > stops[dx | (dz << 4)];
                    assertEquals(!destinationFull, (retained & (1 << direction.ordinal())) != 0);
                }
            }
        }
    }

    @Test void earlierSeedsAndSidedSourceCallbacksRemainUntouched() throws Exception {
        var engine = engine(0, 0);
        Arrays.fill(stops(engine), -65);
        long prefix = seed(engine, 8, 8, 8);
        long sided = prefix | PulsarEngine.FLAG_HAS_SIDED_TRANSPARENT_BLOCKS;
        engine.appendToIncreaseQueue(prefix);
        engine.appendToIncreaseQueue(prefix);
        engine.appendToIncreaseQueue(sided);
        trim(engine, 1);
        assertEquals(2, engine.increaseQueueInitialLength);
        assertEquals(prefix, engine.increaseQueue[0]);
        assertEquals(sided, engine.increaseQueue[1]);
    }
}
