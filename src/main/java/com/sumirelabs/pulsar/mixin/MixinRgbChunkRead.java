package com.sumirelabs.pulsar.mixin;

import net.minecraft.world.chunk.Chunk;
import net.minecraft.network.PacketBuffer;
import com.sumirelabs.pulsar.rgb.RgbClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Chunk.class)
public abstract class MixinRgbChunkRead {
    @Inject(method = "read", at = @At("RETURN"))
    private void pulsar$refreshRgbSky(PacketBuffer buffer, int mask, boolean full, CallbackInfo ci) {
        RgbClient.chunkRead((Chunk) (Object) this);
    }
}
