package com.sumirelabs.pulsar.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.sumirelabs.pulsar.rgb.DynamicProgramHandle;

@Pseudo
@Mixin(targets = "org.embeddedt.embeddium.impl.gl.GlObject", remap = false)
public interface MixinDynamicCeleritasGlObject extends DynamicProgramHandle {

    @Accessor("handle")
    int pulsar$programHandle();

}
