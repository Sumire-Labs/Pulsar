/*
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.sumirelabs.pulsar.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.sumirelabs.pulsar.light.LightUpdateTracer;

@Pseudo
@Mixin(targets = "org.taumc.celeritas.impl.render.terrain.VintageRenderSectionManager", remap = false)
public abstract class MixinCeleritasUpdateTrace {

    @Inject(method = "scheduleSectionForRebuild(IIIZ)V", at = @At("HEAD"), require = 0)
    private void pulsar$traceSchedule(int x, int y, int z, boolean important, CallbackInfo ci) {
        LightUpdateTracer.record(LightUpdateTracer.Kind.CELERITAS_SCHEDULE, Integer.MIN_VALUE, x, y, z);
    }

}
