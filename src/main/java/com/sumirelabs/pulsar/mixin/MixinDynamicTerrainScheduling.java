package com.sumirelabs.pulsar.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.sumirelabs.pulsar.dynamic.DynamicLightRenderUpdate;

import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;

@Mixin(RenderGlobal.class)
public abstract class MixinDynamicTerrainScheduling {

    // setupTerrain clears the dirty flag after this call. Only bypass the immediate
    // build when the normal dispatcher actually accepted the replacement mesh.
    @WrapOperation(method = "setupTerrain", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/ChunkRenderDispatcher;updateChunkNow(Lnet/minecraft/client/renderer/chunk/RenderChunk;)Z"), require = 0)
    private boolean pulsar$scheduleLightMesh(ChunkRenderDispatcher dispatcher, RenderChunk chunk,
        Operation<Boolean> original) {
        if (((DynamicLightRenderUpdate) chunk).pulsar$onlyDynamicLight()
            && chunk.getCompiledChunk() != CompiledChunk.DUMMY && dispatcher.updateChunkLater(chunk))
            return true;
        return original.call(dispatcher, chunk);
    }

}
