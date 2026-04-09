package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.world.PulsarWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tracks player-initiated block changes on the client. Pulsar uses this flag
 * to fast-path the renderer's response to player place/break events so the
 * visible result tracks the player's action even before the asynchronous BFS
 * worker has finished propagating.
 *
 * <p>The first release only tracks block destruction; right-click placement
 * tracking can be added later by injecting into
 * {@code processRightClickBlock} once that method's signature is verified
 * against the deobfuscation in use.
 */
@Mixin(PlayerControllerMP.class)
public abstract class MixinPlayerControllerMP {

    @Inject(method = "onPlayerDestroyBlock", at = @At("HEAD"))
    private void pulsar$beginDestroy(final BlockPos pos, final CallbackInfoReturnable<Boolean> cir) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.world != null) {
            ((PulsarWorld) mc.world).pulsar$setPlayerAction(true);
        }
    }

    @Inject(method = "onPlayerDestroyBlock", at = @At("RETURN"))
    private void pulsar$endDestroy(final BlockPos pos, final CallbackInfoReturnable<Boolean> cir) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.world != null) {
            ((PulsarWorld) mc.world).pulsar$setPlayerAction(false);
        }
    }
}
