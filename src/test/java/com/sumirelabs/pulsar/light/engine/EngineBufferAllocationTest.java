package com.sumirelabs.pulsar.light.engine;

import com.sumirelabs.pulsar.util.WorldHeightContext;
import net.minecraft.init.Bootstrap;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EngineBufferAllocationTest {
    @Test void unusedQueuesStayEmptyButFirstWorkGetsNormalCapacity()throws Exception {
        Bootstrap.register();
        var unsafe=Class.forName("sun.misc.Unsafe");var singleton=unsafe.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        World world=(World)unsafe.getMethod("allocateInstance",Class.class).invoke(singleton.get(null),WorldServer.class);
        var engine=new ScalarBlockEngine(world,WorldHeightContext.VANILLA);
        assertEquals(0,engine.increaseQueue.length);assertEquals(0,engine.decreaseQueue.length);
        assertEquals(0,engine.notifyBoundsCache.length);
        assertTrue(engine.appendToIncreaseQueue(42));assertTrue(engine.appendToDecreaseQueue(17));
        assertEquals(PulsarEngine.INITIAL_QUEUE_SIZE,engine.increaseQueue.length);
        assertEquals(PulsarEngine.INITIAL_QUEUE_SIZE,engine.decreaseQueue.length);
        assertEquals(42,engine.increaseQueue[0]);assertEquals(17,engine.decreaseQueue[0]);
        var remote=World.class.getDeclaredField("isRemote");remote.setAccessible(true);remote.setBoolean(world,true);
        var client=new ScalarBlockEngine(world,WorldHeightContext.VANILLA);
        assertTrue(client.notifyBoundsCache.length>0,"Client render bounds must remain available");
    }
}
