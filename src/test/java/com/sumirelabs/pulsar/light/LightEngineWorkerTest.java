package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.light.engine.PulsarEngine;
import com.sumirelabs.pulsar.light.engine.ScalarBlockEngine;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LightEngineWorkerTest {
    @Test
    void ordinaryTasksReuseOneEngineButReentrantTasksCannotShareItsMutableCaches() throws Exception {
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
            if (calls.size() == 1) assertTrue(holder[0].processOnePendingUntil(Long.MAX_VALUE));
        }, new AtomicInteger(), "test", "unused", false, world, new AtomicInteger());
        try {
            queue.queueBlockChange(0, 64, 0);
            queue.queueBlockChange(96, 64, 0);
            assertTrue(holder[0].processOnePendingUntil(Long.MAX_VALUE));
            assertEquals(2, calls.size());
            assertNotSame(calls.get(0), calls.get(1));
            queue.queueBlockChange(192, 64, 0);
            assertTrue(holder[0].processOnePendingUntil(Long.MAX_VALUE));
            assertSame(calls.get(0), calls.get(2));
            assertEquals(2, created.get());
            assertFalse(queue.hasWork());
        } finally {
            holder[0].requestStop();
            holder[0].awaitStop();
        }
    }
}
