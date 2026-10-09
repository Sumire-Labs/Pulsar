package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.light.engine.PulsarEngine;
import com.sumirelabs.pulsar.config.PulsarConfig;
import com.sumirelabs.pulsar.light.engine.ScalarBlockEngine;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LightEngineWorkerTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 4})
    void ordinaryTasksReuseOneEngineButReentrantTasksCannotShareItsMutableCaches(int cacheSize) throws Exception {
        final int previousCacheSize = PulsarConfig.performance.cachedEnginesPerLane;
        PulsarConfig.performance.cachedEnginesPerLane = cacheSize;
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        var singleton = unsafeType.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        // The engine constructor reads isRemote only; no live world operations.
        World world = (World) unsafeType.getMethod("allocateInstance", Class.class)
                .invoke(singleton.get(null), WorldServer.class);
        var queue = new LightQueue(WorldHeightContext.VANILLA);
        var calls = new ArrayList<PulsarEngine>();
        var created = new AtomicInteger();
        LightEngineWorker[] holder = new LightEngineWorker[1];
        holder[0] = new LightEngineWorker(queue, () -> {
            created.incrementAndGet();
            return new ScalarBlockEngine(world, WorldHeightContext.VANILLA);
        }, (task, engine) -> {
            calls.add(engine);
            if ((calls.size() & 1) == 1) assertTrue(holder[0].processOnePendingUntil(Long.MAX_VALUE));
        }, new AtomicInteger(), "test", "unused", false, world, new AtomicInteger());
        try {
            queue.queueBlockChange(0, 64, 0);
            queue.queueBlockChange(96, 64, 0);
            assertTrue(holder[0].processOnePendingUntil(Long.MAX_VALUE));
            assertEquals(2, calls.size());
            assertNotSame(calls.get(0), calls.get(1));
            queue.queueBlockChange(192, 64, 0);
            queue.queueBlockChange(288, 64, 0);
            assertTrue(holder[0].processOnePendingUntil(Long.MAX_VALUE));
            assertSame(calls.get(0), calls.get(2));
            assertEquals(cacheSize == 0 ? 3 : 2, created.get());
            if (cacheSize == 0) assertNotSame(calls.get(1), calls.get(3));
            else assertSame(calls.get(1), calls.get(3));
            assertFalse(queue.hasWork());
        } finally {
            holder[0].requestStop();
            holder[0].awaitStop();
            PulsarConfig.performance.cachedEnginesPerLane = previousCacheSize;
        }
    }
}
