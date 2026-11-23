package com.sumirelabs.pulsar.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.sumirelabs.pulsar.dynamic.DynamicLightClient;
import com.sumirelabs.pulsar.dynamic.DynamicLightRebuilds;
import com.sumirelabs.pulsar.dynamic.DynamicLightRenderUpdate;

import net.minecraft.client.renderer.chunk.ChunkCompileTaskGenerator;
import net.minecraft.client.renderer.chunk.RenderChunk;

@Mixin(RenderChunk.class)
public abstract class MixinDynamicRenderChunk implements DynamicLightRenderUpdate {

    @Shadow private boolean needsUpdate;
    @Unique private boolean pulsar$dynamicOnly;

    @Inject(method = "setNeedsUpdate", at = @At("HEAD"))
    private void pulsar$recordUpdate(boolean immediate,
        CallbackInfo ci) {
        pulsar$dynamicOnly = !immediate && DynamicLightRebuilds.markingDynamicLight()
            && (!needsUpdate || pulsar$dynamicOnly);
    }
    public boolean pulsar$onlyDynamicLight() {
        return needsUpdate && pulsar$dynamicOnly;
    }

    @WrapMethod(method = "rebuildChunk")
    private void pulsar$dynamicSnapshot(float x, float y, float z, ChunkCompileTaskGenerator task,
        Operation<Void> original) {
        try (var scope = DynamicLightClient.beginMesh()) {
            original.call(x, y, z, task);
        }
    }

}
