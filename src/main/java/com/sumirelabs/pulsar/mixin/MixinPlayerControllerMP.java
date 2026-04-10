package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.config.PulsarConfig;
import com.sumirelabs.pulsar.world.PulsarWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
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
 * <p>Both block destruction ({@code onPlayerDestroyBlock}) and right-click
 * placement ({@code processRightClickBlock}) are wrapped symmetrically: the
 * {@link PulsarWorld#pulsar$setPlayerAction(boolean) playerAction} flag is
 * raised at {@code @At("HEAD")} and lowered at {@code @At("RETURN")} so that
 * any {@code checkLightFor} call made from inside the vanilla code runs on
 * the synchronous client-thread path instead of being queued on the BFS
 * worker. Without this, player-placed torches during a heavy chunk-gen
 * burst could sit behind the initial-light backlog for several hundred ms.
 */
@Mixin(PlayerControllerMP.class)
public abstract class MixinPlayerControllerMP {

    @Inject(method = "onPlayerDestroyBlock", at = @At("HEAD"))
    private void pulsar$beginDestroy(final BlockPos pos, final CallbackInfoReturnable<Boolean> cir) {
        if (!PulsarConfig.features.trackPlayerAction) return;
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.world != null) {
            ((PulsarWorld) mc.world).pulsar$setPlayerAction(true);
        }
    }

    @Inject(method = "onPlayerDestroyBlock", at = @At("RETURN"))
    private void pulsar$endDestroy(final BlockPos pos, final CallbackInfoReturnable<Boolean> cir) {
        if (!PulsarConfig.features.trackPlayerAction) return;
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.world != null) {
            ((PulsarWorld) mc.world).pulsar$setPlayerAction(false);
        }
    }

    @Inject(method = "processRightClickBlock", at = @At("HEAD"))
    private void pulsar$beginRightClick(final EntityPlayerSP player, final WorldClient worldIn,
                                        final BlockPos pos, final EnumFacing facing, final Vec3d vec,
                                        final EnumHand hand,
                                        final CallbackInfoReturnable<EnumActionResult> cir) {
        if (!PulsarConfig.features.trackPlayerAction) return;
        if (worldIn != null) {
            ((PulsarWorld) worldIn).pulsar$setPlayerAction(true);
        }
    }

    @Inject(method = "processRightClickBlock", at = @At("RETURN"))
    private void pulsar$endRightClick(final EntityPlayerSP player, final WorldClient worldIn,
                                      final BlockPos pos, final EnumFacing facing, final Vec3d vec,
                                      final EnumHand hand,
                                      final CallbackInfoReturnable<EnumActionResult> cir) {
        if (!PulsarConfig.features.trackPlayerAction) return;
        if (worldIn != null) {
            ((PulsarWorld) worldIn).pulsar$setPlayerAction(false);
        }
    }
}
