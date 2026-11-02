package com.sumirelabs.pulsar.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.sumirelabs.pulsar.dynamic.DynamicLightClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Coerce;

/** Pin dynamic light for both the light cache and RGB vertex colors, including exceptional exits. */
@Pseudo
@Mixin(targets="org.taumc.celeritas.impl.render.terrain.compile.task.ChunkBuilderMeshingTask", remap=false)
public abstract class MixinDynamicCeleritasTask {
    @Coerce
    @WrapMethod(method="execute(Lorg/embeddedt/embeddium/impl/render/chunk/compile/ChunkBuildContext;Lorg/embeddedt/embeddium/impl/util/task/CancellationToken;)Lorg/embeddedt/embeddium/impl/render/chunk/compile/ChunkBuildOutput;", require=0, remap=false)
    private Object pulsar$dynamicSnapshot(@Coerce Object context, @Coerce Object cancellation, Operation<Object> original) {
        try (var scope = DynamicLightClient.beginMesh()) {
            return original.call(context, cancellation);
        }
    }
}
