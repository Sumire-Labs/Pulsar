package com.sumirelabs.pulsar.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.sumirelabs.pulsar.rgb.RgbAmbientFloor;

import net.minecraft.client.renderer.EntityRenderer;

@Mixin(EntityRenderer.class)
public abstract class MixinRgbAmbientLightmap {

    @Shadow private int[] lightmapColors;
    @Inject(method = "updateLightmap", at = @At("RETURN"))
    private void pulsar$ambient(float partial, CallbackInfo ci) {
        RgbAmbientFloor.update(lightmapColors);
    }

}
