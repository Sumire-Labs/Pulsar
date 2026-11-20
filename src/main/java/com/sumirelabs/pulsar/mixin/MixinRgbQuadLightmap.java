package com.sumirelabs.pulsar.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.sumirelabs.pulsar.rgb.RgbQuadLightmap;
@Pseudo
@Mixin(targets = "org.embeddedt.embeddium.impl.model.light.data.QuadLightData", remap = false)
public interface MixinRgbQuadLightmap extends RgbQuadLightmap {

    @Accessor("lm")
    int[] pulsar$lightmap();

}
