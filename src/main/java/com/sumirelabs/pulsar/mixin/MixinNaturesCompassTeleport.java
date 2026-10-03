package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.compat.WorldThreadPacketGate;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Nature's Compass 1.12.2 searches the landing column in its Netty handler. */
@Pseudo
@Mixin(targets = "com.chaosthedude.naturescompass.network.PacketTeleport$Handler", remap = false)
public abstract class MixinNaturesCompassTeleport {
    @SuppressWarnings("unchecked")
    @Inject(method = "onMessage(Lcom/chaosthedude/naturescompass/network/PacketTeleport;"
            + "Lnet/minecraftforge/fml/common/network/simpleimpl/MessageContext;)"
            + "Lnet/minecraftforge/fml/common/network/simpleimpl/IMessage;",
            at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private void pulsar$teleportOnWorldThread(@Coerce final IMessage packet, final MessageContext context,
                                             final CallbackInfoReturnable<IMessage> cir) {
        final NetHandlerPlayServer connection = context.getServerHandler();
        final WorldServer world = connection.player.getServerWorld();
        if (WorldThreadPacketGate.defer(world::isCallingFromMinecraftThread, world::addScheduledTask,
                () -> connection.netManager.isChannelOpen() && connection.player.getServerWorld() == world,
                // Re-enter the original typed handler through Forge's generic bridge.
                // On the world thread the gate falls through, preserving permission/item checks.
                () -> ((IMessageHandler<IMessage, IMessage>) (Object) this).onMessage(packet, context))) {
            cir.setReturnValue(null);
        }
    }
}
