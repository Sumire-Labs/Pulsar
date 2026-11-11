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
    @Inject(method = "writeGeometry", at = @At("HEAD"), require = 0, remap = false)
    private void pulsar$ambientFloor(int x, int y, int z, @Coerce Object builder, net.minecraft.util.math.Vec3d offset,
        @Coerce Object material, @Coerce Object quad, int[] colors, @Coerce Object light, @Coerce Object orientation,
        org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (!pulsar$tinted || RgbShaderRuntime.active()
            || !(light instanceof com.sumirelabs.pulsar.rgb.RgbQuadLightmap data))
            return;
        int[] uv = data.pulsar$lightmap();
        for (int i = 0; i < 4; i++)
            colors[i] = com.sumirelabs.pulsar.rgb.RgbAmbientFloor.preserve(pulsar$material[i], colors[i],
                (uv[i] & 65535) / 16f, (uv[i] >>> 16 & 65535) / 16f);
    }

    @Inject(method = "getVertexColors", at = @At("RETURN"), require = 0, remap = false)
    private void pulsar$colorVertices(BlockPos pos, @Coerce Object provider, @Coerce Object quad,
        CallbackInfoReturnable<int[]> cir) {
        pulsar$tinted = false;
        if (RgbShaderRuntime.active() || !RgbClient.active() || !(quad instanceof BakedQuad baked))
            return;
        RgbClient.celeritasRendered = true;
        int[] vertices = baked.getVertexData(), colors = cir.getReturnValue();
        System.arraycopy(colors, 0, pulsar$material, 0, 4);
        pulsar$tinted = true;
        int stride = baked.getFormat().getIntegerSize();
        for (int i = 0; i < 4; i++)
            colors[i] = RgbClient.tintAbgr(colors[i],
                pos.getX() + Float.intBitsToFloat(vertices[i * stride]),
                pos.getY() + Float.intBitsToFloat(vertices[i * stride + 1]),
                pos.getZ() + Float.intBitsToFloat(vertices[i * stride + 2]));
    }

}
