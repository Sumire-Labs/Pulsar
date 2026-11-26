/*
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.sumirelabs.pulsar.mixin;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.renderer.RegionRenderCacheBuilder;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;

/** Repeated light updates can grow native vertex buffers to tens of MiB per terrain builder. */
@Mixin(ChunkRenderDispatcher.class)
public abstract class MixinTerrainBuilderPool {

    @Shadow
    @Final
    @Mutable private BlockingQueue<RegionRenderCacheBuilder> queueFreeRenderBuilders;
    @Unique private LinkedBlockingDeque<RegionRenderCacheBuilder> pulsar$reusableBuilders;

    @Inject(method = "<init>(I)V", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/chunk/ChunkRenderDispatcher;queueFreeRenderBuilders:Ljava/util/concurrent/BlockingQueue;", opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER), require = 0)
    private void pulsar$initBuilderPool(int count, CallbackInfo ci) {
        if (queueFreeRenderBuilders.getClass() == ArrayBlockingQueue.class && queueFreeRenderBuilders.isEmpty()) {
            pulsar$reusableBuilders = new LinkedBlockingDeque<>(queueFreeRenderBuilders.remainingCapacity());
            queueFreeRenderBuilders = pulsar$reusableBuilders;
        }
    }

    @WrapOperation(method = "allocateRenderBuilder", at = @At(value = "INVOKE", target = "Ljava/util/concurrent/BlockingQueue;take()Ljava/lang/Object;"), require = 0)
    private Object pulsar$reuseRecentBuilder(BlockingQueue<?> pool, Operation<Object> original)
        throws InterruptedException {
        // Builders are interchangeable. Reusing the last one returned avoids expanding every
        // idle builder in rotation when a few high-detail sections are invalidated repeatedly.
        if (pool == pulsar$reusableBuilders)
            return pulsar$reusableBuilders.takeLast();
        return original.call(pool);
    }

}
