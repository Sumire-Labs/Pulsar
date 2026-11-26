package com.sumirelabs.pulsar.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.sumirelabs.pulsar.dynamic.DynamicLightRenderUpdate;
import com.sumirelabs.pulsar.dynamic.TerrainMeshSize;

import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;

@Mixin(RenderGlobal.class)
public abstract class MixinDynamicTerrainScheduling {

    @Shadow private ChunkRenderDispatcher renderDispatcher;

    @WrapOperation(method = "renderBlockLayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/ChunkRenderDispatcher;updateTransparencyLater(Lnet/minecraft/client/renderer/chunk/RenderChunk;)Z"), require = 0)
    private boolean pulsar$deferResortDuringBuild(ChunkRenderDispatcher dispatcher, RenderChunk chunk,
        Operation<Boolean> original) {
        var update = (DynamicLightRenderUpdate) chunk;
        if (((TerrainMeshSize) chunk.getCompiledChunk()).pulsar$hasLargeLayer() && update.pulsar$rebuildInProgress()) {
            // Vanilla's camera-only resort would cancel the geometry build, including its new light.
            update.pulsar$setResortPending(true);
            return false;
        }
        boolean accepted = original.call(dispatcher, chunk);
        if (accepted && update.pulsar$resortPending())
            update.pulsar$setResortPending(false);
        return accepted;
    }

    @WrapOperation(method = "setupTerrain", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/RenderChunk;needsUpdate()Z"), require = 0)
    private boolean pulsar$waitForLargeRebuild(RenderChunk chunk, Operation<Boolean> original) {
        // The visible-section scan runs every frame. Leave the dirty flag set so an edit
        // received during compilation gets a fresh build after the current upload completes.
        boolean dirty = original.call(chunk);
        var update = (DynamicLightRenderUpdate) chunk;
        if (!dirty && !update.pulsar$resortPending())
            return false;
        var compiled = chunk.getCompiledChunk();
        if (((TerrainMeshSize) compiled).pulsar$hasLargeLayer() && update.pulsar$rebuildInProgress())
            return false;
        if (!dirty && (compiled.getState() == null || renderDispatcher.updateTransparencyLater(chunk)))
            update.pulsar$setResortPending(false);
        return dirty;
    }

    // setupTerrain clears the dirty flag after this call. Only bypass the immediate
    // build when the normal dispatcher actually accepted the replacement mesh.
    @WrapOperation(method = {"setupTerrain",
        "updateChunks"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/ChunkRenderDispatcher;updateChunkNow(Lnet/minecraft/client/renderer/chunk/RenderChunk;)Z"), require = 0)
    private boolean pulsar$scheduleLightMesh(ChunkRenderDispatcher dispatcher, RenderChunk chunk,
        Operation<Boolean> original) {
        CompiledChunk compiled = chunk.getCompiledChunk();
        if (compiled != CompiledChunk.DUMMY
            && (((DynamicLightRenderUpdate) chunk).pulsar$onlyDynamicLight()
                || ((TerrainMeshSize) compiled).pulsar$hasLargeLayer())
            && dispatcher.updateChunkLater(chunk))
            return true;
        return original.call(dispatcher, chunk);
    }

}
