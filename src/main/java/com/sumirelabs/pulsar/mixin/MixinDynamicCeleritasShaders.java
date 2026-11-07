package com.sumirelabs.pulsar.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.sumirelabs.pulsar.rgb.DynamicTerrainShader;

@Pseudo
@Mixin(targets = "org.embeddedt.embeddium.impl.render.shader.ShaderLoader", remap = false)
public abstract class MixinDynamicCeleritasShaders {

    @Inject(method = "getShaderSource", at = @At("RETURN"), cancellable = true, require = 0)
    private static void pulsar$frameLight(String name, CallbackInfoReturnable<String> cir) {
        cir.setReturnValue(DynamicTerrainShader.patch(name, cir.getReturnValue()));
    }

}
