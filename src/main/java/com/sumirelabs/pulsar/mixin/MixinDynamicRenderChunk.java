package com.sumirelabs.pulsar.mixin;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.sumirelabs.pulsar.dynamic.DynamicLightClient;

import net.minecraft.client.renderer.chunk.ChunkCompileTaskGenerator;
import net.minecraft.client.renderer.chunk.RenderChunk;

@Mixin(RenderChunk.class)
public abstract class MixinDynamicRenderChunk {

    @WrapMethod(method = "rebuildChunk")
    private void pulsar$dynamicSnapshot(float x, float y, float z, ChunkCompileTaskGenerator task,
        Operation<Void> original) {
        try (var scope = DynamicLightClient.beginMesh()) {
            original.call(x, y, z, task);
        }
    }

}
