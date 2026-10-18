/* SPDX-License-Identifier: LGPL-3.0-only */
package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.rgb.RgbShaderRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.coderbot.iris.samplers.IrisSamplers", remap = false)
public abstract class MixinRgbShaderSamplers {
    @Inject(method = "addLevelSamplers", at = @At("RETURN"), require = 0, remap = false)
    private static void pulsar$samplers(@Coerce Object holder, @Coerce Object pipeline,
                                        @Coerce Object white, @Coerce Object inputs, CallbackInfo ci) {
        RgbShaderRuntime.registerSamplers(holder);
    }
    @Inject(method = "addCompositeSamplers", at = @At("RETURN"), require = 0, remap = false)
    private static void pulsar$deferredSamplers(@Coerce Object holder, @Coerce Object targets, CallbackInfo ci) {
        RgbShaderRuntime.registerSamplers(holder);
    }
}
