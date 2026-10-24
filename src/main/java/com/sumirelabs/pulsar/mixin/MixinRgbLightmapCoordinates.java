package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.rgb.RgbDrawContext;
import net.minecraft.client.renderer.OpenGlHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(OpenGlHelper.class)
public abstract class MixinRgbLightmapCoordinates {
    @Inject(method="setLightmapTextureCoords",at=@At("RETURN"))
    private static void pulsar$lightmapCoordinates(int unit,float u,float v,CallbackInfo ci) {
        RgbDrawContext.coordinates(unit,u,v);
    }
}
