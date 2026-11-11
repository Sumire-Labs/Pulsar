/*
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.sumirelabs.pulsar.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.renderer.GlStateManager;

@Mixin(GlStateManager.class)
public interface MixinRgbActiveTexture {

    @Accessor("activeTextureUnit")
    static int pulsar$activeTextureIndex() {
        throw new AssertionError();
    }

}
