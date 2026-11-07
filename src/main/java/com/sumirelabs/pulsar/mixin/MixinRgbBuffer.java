package com.sumirelabs.pulsar.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.sumirelabs.pulsar.rgb.RgbBuffer;

import net.minecraft.client.renderer.BufferBuilder;

@Mixin(BufferBuilder.class)
public interface MixinRgbBuffer extends RgbBuffer {

    @Accessor("xOffset")
    double pulsar$xOffset();
    @Accessor("yOffset")
    double pulsar$yOffset();
    @Accessor("zOffset")
    double pulsar$zOffset();

}
