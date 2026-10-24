/* SPDX-License-Identifier: LGPL-3.0-only */
package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.compat.LoginProtocolOrder;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetworkManager;
import net.minecraftforge.fml.common.network.handshake.NetworkDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Only the pre-negotiation server transition is ordered; packet encoding is unchanged. */
@Mixin(NetworkDispatcher.class)
public abstract class MixinForgeLoginProtocol {
    @Redirect(method = "serverInitiateHandshake", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/NetworkManager;setConnectionState(Lnet/minecraft/network/EnumConnectionState;)V",
            remap = true), require = 1)
    private void pulsar$orderPlayAfterLogin(NetworkManager manager, EnumConnectionState state) {
        // Preserve Forge's world/player initialization on its original thread.
        // Only the channel state change joins NetworkManager's existing FIFO.
        LoginProtocolOrder.advance(manager.channel(), () -> manager.setConnectionState(state));
    }
}
