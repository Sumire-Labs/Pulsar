/* SPDX-License-Identifier: LGPL-3.0-only */
package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.rgb.RgbShaderRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.coderbot.iris.uniforms.CommonUniforms", remap = false)
public abstract class MixinRgbShaderUniforms {
    @Inject(method = "addNonDynamicUniforms", at = @At("RETURN"), require = 0, remap = false)
    private static void pulsar$uniforms(@Coerce Object holder, @Coerce Object ids,
                                       @Coerce Object directives, @Coerce Object notifier, CallbackInfo ci) {
        RgbShaderRuntime.registerUniforms(holder, notifier);
    }
}
