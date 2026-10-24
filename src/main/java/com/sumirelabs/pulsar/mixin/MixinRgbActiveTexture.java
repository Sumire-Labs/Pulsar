package com.sumirelabs.pulsar.mixin;

import net.minecraft.client.renderer.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GlStateManager.class)
public interface MixinRgbActiveTexture {
    @Accessor("activeTextureUnit") static int pulsar$activeTextureIndex() { throw new AssertionError(); }
}
