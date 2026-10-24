package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.light.CeleritasLightCache;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

@Pseudo
@Mixin(targets = "org.taumc.celeritas.impl.render.terrain.compile.light.LightDataCache", remap = false)
public abstract class MixinCeleritasLightCache implements CeleritasLightCache {
    @Shadow @Final private IBlockAccess world;

    public IBlockAccess pulsar$lightWorld() { return world; }
}
