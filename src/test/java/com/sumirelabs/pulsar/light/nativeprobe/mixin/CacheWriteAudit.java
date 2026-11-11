/*
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.sumirelabs.pulsar.light.nativeprobe.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.sumirelabs.pulsar.light.SWMRNibbleArray;
import com.sumirelabs.pulsar.light.nativeprobe.RegionWriteAudit;

@Mixin(targets = "com.sumirelabs.pulsar.light.engine.LightEngineCache", remap = false)
public abstract class CacheWriteAudit {

    @Inject(method = "setNibbleInCache", at = @At("HEAD"))
    private void tag(int x, int y, int z, SWMRNibbleArray nibble, CallbackInfo ci) {
        RegionWriteAudit.tag(nibble, x, z);
    }

}
