package com.sumirelabs.pulsar.mixin;

import net.minecraft.client.renderer.BufferBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import com.sumirelabs.pulsar.rgb.RgbBuffer;

@Mixin(BufferBuilder.class)
public interface MixinRgbBuffer extends RgbBuffer {
    @Accessor("xOffset") double pulsar$xOffset();
    @Accessor("yOffset") double pulsar$yOffset();
    @Accessor("zOffset") double pulsar$zOffset();
}
