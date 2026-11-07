package com.sumirelabs.pulsar.light.nativeprobe.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.sumirelabs.pulsar.light.SWMRNibbleArray;
import com.sumirelabs.pulsar.light.nativeprobe.RegionWriteAudit;

@Mixin(value = SWMRNibbleArray.class, remap = false)
public abstract class NibbleWriteAudit {

    @Inject(method = {"set(IIII)V", "set(II)V", "setFull", "setZero", "setNull", "setNonNull", "setUninitialised",
        "setHidden", "extrudeLower"}, at = @At("HEAD"))
    private void writing(CallbackInfo ci) {
        RegionWriteAudit.writing((SWMRNibbleArray) (Object) this);
    }
    @Inject(method = "updateVisible", at = @At("HEAD"))
    private void publishing(CallbackInfoReturnable<Boolean> ci) {
        RegionWriteAudit.writing((SWMRNibbleArray) (Object) this);
    }

}
