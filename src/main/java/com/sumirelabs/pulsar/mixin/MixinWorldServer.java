package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.light.WorldLightManager;
import com.sumirelabs.pulsar.world.PulsarWorld;
import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldServer.class)
public abstract class MixinWorldServer {

    @Inject(method = "tick", at = @At("TAIL"))
    private void pulsar$tickLightManager(final CallbackInfo ci) {
        final WorldLightManager mgr = ((PulsarWorld) (Object) this).pulsar$getLightManager();
        if (mgr != null) {
            mgr.scheduleUpdate();
        }
    }
}
