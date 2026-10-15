package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.light.WorldLightManager;
import com.sumirelabs.pulsar.world.PulsarWorld;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.management.PlayerChunkMap;
import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerChunkMap.class)
public abstract class MixinPlayerChunkMap {
    @Shadow public abstract WorldServer getWorldServer();

    @Inject(method = "isPlayerWatchingChunk", at = @At("RETURN"), cancellable = true)
    private void pulsar$waitForInitialPacket(final EntityPlayerMP player, final int cx, final int cz,
                                            final CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) return;
        final WorldLightManager manager = ((PulsarWorld) this.getWorldServer()).pulsar$getLightManager();
        if (manager != null && manager.isPendingChunkWatch(cx, cz, player)) cir.setReturnValue(false);
    }
}
