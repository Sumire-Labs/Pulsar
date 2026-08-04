package com.sumirelabs.pulsar.util;

import com.sumirelabs.pulsar.compat.DepthsUpdateBridge;
import net.minecraft.world.World;

/**
 * Resolves immutable lighting bounds for a world.
 */
public final class WorldUtil {

    private WorldUtil() {
    }

    public static WorldHeightContext getHeightContext(final World world) {
        return DepthsUpdateBridge.getHeightContext(world);
    }
}
