package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.light.LightUpdateTracer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets="org.taumc.celeritas.impl.render.terrain.compile.task.ChunkBuilderMeshingTask",remap=false)
public abstract class MixinCeleritasBuildTrace {
    @Unique private long pulsar$traceStarted;
    @Inject(method="execute(Lorg/embeddedt/embeddium/impl/render/chunk/compile/ChunkBuildContext;Lorg/embeddedt/embeddium/impl/util/task/CancellationToken;)Lorg/embeddedt/embeddium/impl/render/chunk/compile/ChunkBuildOutput;",at=@At("HEAD"),require=0)
    private void pulsar$traceStart(CallbackInfoReturnable<Object> cir) {
        pulsar$traceStarted=LightUpdateTracer.enabled()?System.nanoTime():0;
    }
    @Inject(method="execute(Lorg/embeddedt/embeddium/impl/render/chunk/compile/ChunkBuildContext;Lorg/embeddedt/embeddium/impl/util/task/CancellationToken;)Lorg/embeddedt/embeddium/impl/render/chunk/compile/ChunkBuildOutput;",at=@At("RETURN"),require=0)
    private void pulsar$traceFinish(CallbackInfoReturnable<Object> cir) {
        if(cir.getReturnValue()!=null)LightUpdateTracer.completed(pulsar$traceStarted);
        pulsar$traceStarted=0;
    }
}
