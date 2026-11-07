package com.sumirelabs.pulsar.mixin;
import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.sumirelabs.pulsar.rgb.DynamicObjectLight;

import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;

@Mixin(RenderManager.class)
public abstract class MixinDynamicEntityLight {

    @WrapMethod(method = "renderEntityStatic")
    private void pulsar$objectLight(Entity entity, float partial, boolean debug, Operation<Void> original) {
        try (var light = DynamicObjectLight.begin(entity, partial)) {
            original.call(entity, partial, debug);
        }
    }

}
