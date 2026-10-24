package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.rgb.RgbNetwork;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.SPacketChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Full vanilla packets replace client chunks, so every ready chunk packet must precede its color. */
@Mixin(NetHandlerPlayServer.class)
public abstract class MixinRgbChunkSend {
    @Shadow public EntityPlayerMP player;
    @Inject(method = "sendPacket", at = @At("RETURN"))
    private void pulsar$sendColorAfterChunk(Packet<?> packet, CallbackInfo ci) {
        if (packet instanceof SPacketChunkData chunk) RgbNetwork.afterChunkPacket(player, chunk.getChunkX(), chunk.getChunkZ());
    }
}
