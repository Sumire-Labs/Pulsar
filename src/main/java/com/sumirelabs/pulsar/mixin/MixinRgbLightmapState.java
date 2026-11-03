package com.sumirelabs.pulsar.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.sumirelabs.pulsar.rgb.RgbDrawContext;

import net.minecraft.client.renderer.EntityRenderer;

@Mixin(EntityRenderer.class)
public abstract class MixinRgbLightmapState {

    @Inject(method = "enableLightmap", at = @At("RETURN"))
    private void pulsar$lightmapOn(CallbackInfo ci) {
        RgbDrawContext.lightmap(true);
    }
    @Inject(method = "disableLightmap", at = @At("RETURN"))
    private void pulsar$lightmapOff(CallbackInfo ci) {
        RgbDrawContext.lightmap(false);
    }

}
