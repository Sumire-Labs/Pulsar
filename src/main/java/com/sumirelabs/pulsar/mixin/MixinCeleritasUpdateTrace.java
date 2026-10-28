package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.light.LightUpdateTracer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets="org.taumc.celeritas.impl.render.terrain.VintageRenderSectionManager",remap=false)
public abstract class MixinCeleritasUpdateTrace {
    @Inject(method="scheduleSectionForRebuild(IIIZ)V",at=@At("HEAD"),require=0)
    private void pulsar$traceSchedule(int x,int y,int z,boolean important,CallbackInfo ci) {
        LightUpdateTracer.record(LightUpdateTracer.Kind.CELERITAS_SCHEDULE,Integer.MIN_VALUE,x,y,z);
    }
}
