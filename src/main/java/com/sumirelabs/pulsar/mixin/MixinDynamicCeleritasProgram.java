package com.sumirelabs.pulsar.mixin;
import com.sumirelabs.pulsar.rgb.DynamicTerrainRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets="org.embeddedt.embeddium.impl.gl.shader.GlProgram",remap=false)
public abstract class MixinDynamicCeleritasProgram {
    @Inject(method="bind",at=@At("RETURN"),require=0)
    private void pulsar$frameUniforms(CallbackInfo ci){DynamicTerrainRuntime.bind(((com.sumirelabs.pulsar.rgb.DynamicProgramHandle)this).pulsar$programHandle());}
    @Inject(method="unbind",at=@At("HEAD"),require=0)
    private void pulsar$restoreTextures(CallbackInfo ci){DynamicTerrainRuntime.unbind();}
    @Inject(method="destroyInternal",at=@At("HEAD"),require=0)
    private void pulsar$discardProgram(CallbackInfo ci){DynamicTerrainRuntime.deleted(((com.sumirelabs.pulsar.rgb.DynamicProgramHandle)this).pulsar$programHandle());}
}
