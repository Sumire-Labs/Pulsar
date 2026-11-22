package com.sumirelabs.pulsar.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.sumirelabs.pulsar.rgb.RgbClient;
import com.sumirelabs.pulsar.rgb.RgbShaderRuntime;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.util.math.BlockPos;

/** Optional vintage mesh paths; shader-owned passes keep their original material colors. */
@Pseudo
@Mixin(targets = {"org.taumc.celeritas.impl.render.terrain.compile.pipeline.VintageBlockRenderer",
    "com.dhj.actinium.render.terrain.compile.pipeline.VintageBlockRenderer"}, remap = false)
public abstract class MixinRgbCeleritasRenderer {

    @org.spongepowered.asm.mixin.Unique private final int[] pulsar$material = new int[4];
    @org.spongepowered.asm.mixin.Unique private boolean pulsar$tinted;
    @org.spongepowered.asm.mixin.Unique private final float[] pulsar$surface = new float[4];
    @org.spongepowered.asm.mixin.Unique private final float[] pulsar$vertexLight = new float[4];
    @org.spongepowered.asm.mixin.Unique private boolean pulsar$dynamic;
    @Inject(method = "writeGeometry", at = @At("HEAD"), require = 0, remap = false)
    private void pulsar$ambientFloor(int x, int y, int z, @Coerce Object builder, net.minecraft.util.math.Vec3d offset,
        @Coerce Object material, @Coerce Object quad, int[] colors, @Coerce Object light, @Coerce Object orientation,
        org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if ((!pulsar$tinted && !pulsar$dynamic) || RgbShaderRuntime.active()
            || !(light instanceof com.sumirelabs.pulsar.rgb.RgbQuadLightmap data))
            return;
        int[] uv = data.pulsar$lightmap();
        for (int i = 0; i < 4; i++) {
            if (pulsar$dynamic)
                uv[i] = com.sumirelabs.pulsar.dynamic.DynamicLightSurface.lightmap(uv[i], pulsar$vertexLight[i]);
            if (pulsar$tinted)
                colors[i] = com.sumirelabs.pulsar.rgb.RgbAmbientFloor.preserve(pulsar$material[i], colors[i],
                    (uv[i] & 65535) / 16f, (uv[i] >>> 16 & 65535) / 16f);
        }
    }

    @Inject(method = "getVertexColors", at = @At("RETURN"), require = 0, remap = false)
    private void pulsar$colorVertices(BlockPos pos, @Coerce Object provider, @Coerce Object quad,
        CallbackInfoReturnable<int[]> cir) {
        pulsar$tinted = false;
        pulsar$dynamic = false;
        if (RgbShaderRuntime.active() || !(quad instanceof BakedQuad baked))
            return;
        pulsar$tinted = RgbClient.active();
        pulsar$dynamic = !com.sumirelabs.pulsar.dynamic.DynamicLightClient.visualFields().isEmpty();
        if (!pulsar$tinted && !pulsar$dynamic)
            return;
        RgbClient.celeritasRendered = true;
        int[] vertices = baked.getVertexData(), colors = cir.getReturnValue();
        System.arraycopy(colors, 0, pulsar$material, 0, 4);
        int stride = baked.getFormat().getIntegerSize();
        var face = baked.getFace();
        for (int i = 0; i < 4; i++) {
            double x = pos.getX() + Float.intBitsToFloat(vertices[i * stride]);
            double y = pos.getY() + Float.intBitsToFloat(vertices[i * stride + 1]);
            double z = pos.getZ() + Float.intBitsToFloat(vertices[i * stride + 2]);
            if (pulsar$dynamic) {
                com.sumirelabs.pulsar.dynamic.DynamicLightClient.sampleSurface(x, y, z, face.getXOffset(),
                    face.getYOffset(), face.getZOffset(), pulsar$surface);
                pulsar$vertexLight[i] = com.sumirelabs.pulsar.dynamic.DynamicLightClient
                    .surfaceBrightness(pulsar$surface);
            }
            if (pulsar$tinted)
                colors[i] = RgbClient.tintAbgr(colors[i], x, y, z, pulsar$dynamic ? pulsar$surface : null);
        }
    }

}
