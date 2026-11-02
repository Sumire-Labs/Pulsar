package com.sumirelabs.pulsar.mixin;
import com.sumirelabs.pulsar.rgb.DynamicProgramHandle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets="org.embeddedt.embeddium.impl.gl.GlObject",remap=false)
public interface MixinDynamicCeleritasGlObject extends DynamicProgramHandle {
    @Accessor("handle") int pulsar$programHandle();
}
