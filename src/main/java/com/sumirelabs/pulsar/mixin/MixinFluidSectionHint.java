/* SPDX-License-Identifier: LGPL-3.0-only */
package com.sumirelabs.pulsar.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.sumirelabs.pulsar.compat.FluidSectionHint;

@Pseudo
@Mixin(targets = "git.jbredwards.fluidlogged_api.mod.common.capability.FluidStateCapabilityVanilla", remap = false)
public abstract class MixinFluidSectionHint implements FluidSectionHint {

    @Unique private volatile int pulsar$occupied;

    // Publish before mutation. Removals may leave false positives, never false negatives.
    @Inject(method = "setFluidState(CLgit/jbredwards/fluidlogged_api/api/util/FluidState;)Z", at = @At("HEAD"), remap = false, require = 0)
    private void pulsar$observe(char position, @Coerce Object fluid, CallbackInfoReturnable<Boolean> ci) {
        int bit = 1 << (position >>> 12);
        if ((pulsar$occupied & bit) == 0)
            synchronized (this) {
                pulsar$occupied |= bit;
            }
    }
    public int pulsar$fluidSections() {
        return pulsar$occupied;
    }

}
