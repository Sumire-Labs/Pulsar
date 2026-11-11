/*
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.sumirelabs.pulsar.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.sumirelabs.pulsar.rgb.RgbClient;

import net.minecraft.network.PacketBuffer;
import net.minecraft.world.chunk.Chunk;

@Mixin(Chunk.class)
public abstract class MixinRgbChunkRead {

    @Inject(method = "read", at = @At("RETURN"))
    private void pulsar$refreshRgbSky(PacketBuffer buffer, int mask, boolean full, CallbackInfo ci) {
        RgbClient.chunkRead((Chunk) (Object) this);
    }

}
