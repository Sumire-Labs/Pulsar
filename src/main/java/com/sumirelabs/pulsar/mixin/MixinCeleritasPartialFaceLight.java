package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.light.CeleritasLightCache;
import com.sumirelabs.pulsar.light.PartialFaceLight;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "org.embeddedt.embeddium.impl.model.light.smooth.SmoothLightPipeline", remap = false)
public abstract class MixinCeleritasPartialFaceLight {
    @Unique private IBlockAccess pulsar$surfaceWorld;
    @Unique private final BlockPos.MutableBlockPos pulsar$surfacePos = new BlockPos.MutableBlockPos();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void pulsar$captureWorld(@Coerce Object cache, @Coerce Object diffuse, boolean normals, CallbackInfo ci) {
        pulsar$surfaceWorld = cache instanceof CeleritasLightCache bridge ? bridge.pulsar$lightWorld() : null;
    }

    @Inject(method = "calculate", at = @At("RETURN"))
    private void pulsar$lightActualSurface(@Coerce Object view, int x, int y, int z, @Coerce Object output,
                                           @Coerce Object cullFace, @Coerce Object lightFace,
                                           boolean shade, boolean enhanced, CallbackInfo ci) {
        if (pulsar$surfaceWorld == null || !(view instanceof BakedQuad quad)) return;
        pulsar$surfacePos.setPos(x, y, z);
        IBlockState state = pulsar$surfaceWorld.getBlockState(pulsar$surfacePos);
        if (!PartialFaceLight.applies(pulsar$surfaceWorld, state, pulsar$surfacePos, quad.getFace())) return;
        state = state.getActualState(pulsar$surfaceWorld, pulsar$surfacePos);
        int[] lights = ((MixinCeleritasQuadLight) output).pulsar$surfaceLight();
        int[] vertices = quad.getVertexData();
        int stride = quad.getFormat().getIntegerSize();
        for (int vertex = 0; vertex < 4; vertex++) {
            int index = vertex * stride;
            lights[vertex] = PartialFaceLight.sample(pulsar$surfaceWorld, state, pulsar$surfacePos, quad.getFace(),
                    Float.intBitsToFloat(vertices[index]), Float.intBitsToFloat(vertices[index + 1]),
                    Float.intBitsToFloat(vertices[index + 2]), lights[vertex]);
        }
    }
}
