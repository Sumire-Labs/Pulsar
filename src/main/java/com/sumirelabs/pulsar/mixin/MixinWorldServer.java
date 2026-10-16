package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.light.WorldLightManager;
import com.sumirelabs.pulsar.world.PulsarWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldServer.class)
public abstract class MixinWorldServer {

    /**
     * Pulsar already queues initial lighting and actual block/fluid changes.
     * Vanilla's random repair probe runs immediately before chunk sending;
     * with Fluidlogged it creates contextual samples which cannot be published
     * until tick end, starving initial packets on every tick. Keep vanilla's
     * random selection but skip the redundant lighting request.
     */
    @Redirect(method = "playerCheckLight", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/WorldServer;checkLight(Lnet/minecraft/util/math/BlockPos;)Z"))
    private boolean pulsar$skipRandomLightRepair(final WorldServer world, final BlockPos pos) {
        final WorldLightManager mgr = ((PulsarWorld) world).pulsar$getLightManager();
        return mgr != null || world.checkLight(pos);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void pulsar$tickLightManager(final CallbackInfo ci) {
        final WorldLightManager mgr = ((PulsarWorld) (Object) this).pulsar$getLightManager();
        if (mgr != null) {
            mgr.processDeferredChunkUpdates();
        }
    }
}
