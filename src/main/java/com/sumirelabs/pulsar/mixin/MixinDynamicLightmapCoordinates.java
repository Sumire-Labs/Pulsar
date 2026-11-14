package com.sumirelabs.pulsar.mixin;
import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.sumirelabs.pulsar.rgb.DynamicObjectLight;

import net.minecraft.client.renderer.OpenGlHelper;

@Mixin(OpenGlHelper.class)
public abstract class MixinDynamicLightmapCoordinates {

    @WrapMethod(method = "setLightmapTextureCoords")
    private static void pulsar$fractionalObjectLight(int unit, float u, float v, Operation<Void> original) {
        original.call(unit, DynamicObjectLight.coordinate(unit, u, v), v);
    }

}
