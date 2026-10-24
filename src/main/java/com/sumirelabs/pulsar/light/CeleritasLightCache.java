package com.sumirelabs.pulsar.light;

import net.minecraft.world.IBlockAccess;

/** Optional bridge; no Celeritas classes are loaded by a vanilla client. */
public interface CeleritasLightCache {
    IBlockAccess pulsar$lightWorld();
}
