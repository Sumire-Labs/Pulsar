package com.sumirelabs.pulsar.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DepthsUpdateStorageIndexInvokerTest {

    @Test
    void releasedA12FallsBackToHeightContextStorageMapping() throws Exception {
        final DepthsUpdateStorageIndexInvoker invoker =
                DepthsUpdateStorageIndexInvoker.resolve(
                        A12HeightManager.class, A12HeightContext.class, Object.class);

        assertEquals(23, invoker.invoke(new A12HeightContext(), null, -64));
        assertEquals("HeightContext#toStorageIndex(int)", invoker.getDescription());
    }

    @Test
    void newerDepthsUpdateUsesWorldAwareStorageMapping() throws Exception {
        final DepthsUpdateStorageIndexInvoker invoker =
                DepthsUpdateStorageIndexInvoker.resolve(
                        NewHeightManager.class, A12HeightContext.class, Object.class);

        assertEquals(101, invoker.invoke(new A12HeightContext(), null, 96));
        assertEquals("HeightManager#toStorageIndex(World,int)", invoker.getDescription());
    }

    public static final class A12HeightManager {
        private A12HeightManager() {
        }
    }

    public static final class A12HeightContext {
        public int toStorageIndex(final int blockY) {
            return blockY == -64 ? 23 : -1;
        }
    }

    public static final class NewHeightManager {
        private NewHeightManager() {
        }

        public static int toStorageIndex(final Object world, final int blockY) {
            return (blockY >> 4) + 95;
        }
    }
}
