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
import com.sumirelabs.pulsar.dynamic.TerrainMeshSize;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.chunk.ChunkCompileTaskGenerator;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.BlockRenderLayer;

@Mixin(RenderChunk.class)
public abstract class MixinDynamicRenderChunk implements DynamicLightRenderUpdate {

    @Shadow private boolean needsUpdate;
    @Shadow private ChunkCompileTaskGenerator compileTask;
    @Unique private boolean pulsar$dynamicOnly;
    @Unique private boolean pulsar$resortPending;

    @Inject(method = "postRenderBlocks", at = @At("HEAD"), require = 0)
    private void pulsar$recordLayerSize(BlockRenderLayer layer, float x, float y, float z, BufferBuilder buffer,
        CompiledChunk compiled, CallbackInfo ci) {
        ((TerrainMeshSize) compiled).pulsar$recordLayerSize(buffer.getVertexCount());
    }

    @Inject(method = "setNeedsUpdate", at = @At("HEAD"))
    private void pulsar$recordUpdate(boolean immediate,
        CallbackInfo ci) {
        pulsar$dynamicOnly = !immediate && DynamicLightRebuilds.markingDynamicLight()
            && (!needsUpdate || pulsar$dynamicOnly);
    }
    public boolean pulsar$onlyDynamicLight() {
        return needsUpdate && pulsar$dynamicOnly;
    }
    public boolean pulsar$resortPending() {
        return pulsar$resortPending;
    }
    public void pulsar$setResortPending(boolean pending) {
        pulsar$resortPending = pending;
    }
    @Inject(method = "stopCompileTask", at = @At("HEAD"))
    private void pulsar$forgetOldMesh(CallbackInfo ci) {
        pulsar$resortPending = false;
    }
    public boolean pulsar$rebuildInProgress() {
        var task = compileTask;
        if (task == null || task.getType() != ChunkCompileTaskGenerator.Type.REBUILD_CHUNK)
            return false;
        if (!task.getLock().tryLock())
            return true;
        try {
            // A worker may leave a task PENDING after dequeuing it if a neighbour is unloaded.
            var status = task.getStatus();
            return status == ChunkCompileTaskGenerator.Status.COMPILING
                || status == ChunkCompileTaskGenerator.Status.UPLOADING;
        } finally {
            task.getLock().unlock();
        }
    }

    @WrapMethod(method = "rebuildChunk")
    private void pulsar$dynamicSnapshot(float x, float y, float z, ChunkCompileTaskGenerator task,
        Operation<Void> original) {
        try (var scope = DynamicLightClient.beginMesh()) {
            original.call(x, y, z, task);
        }
    }

}
