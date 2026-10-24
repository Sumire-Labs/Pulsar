package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.light.SmoothLightMatrix;
import com.sumirelabs.pulsar.light.PartialFaceLight;
import net.minecraft.client.renderer.color.BlockColors;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.client.model.pipeline.VertexLighterFlat;
import net.minecraftforge.client.model.pipeline.VertexLighterSmoothAo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = VertexLighterSmoothAo.class, remap = false)
public abstract class MixinForgeSmoothLight extends VertexLighterFlat {
    protected MixinForgeSmoothLight(BlockColors colors) { super(colors); }

    @Inject(method = "updateLightmap", at = @At("RETURN"))
    private void pulsar$keepHiddenLightOffSurface(float[] normal, float[] lightmap, float x, float y, float z, CallbackInfo ci) {
        int axis = Math.abs(normal[0]) > .9999f ? 0 : Math.abs(normal[1]) > .9999f ? 1 : Math.abs(normal[2]) > .9999f ? 2 : -1;
        if (axis < 0) return;
        EnumFacing surface = axis == 0 ? (normal[0] > 0 ? EnumFacing.EAST : EnumFacing.WEST)
                : axis == 1 ? (normal[1] > 0 ? EnumFacing.UP : EnumFacing.DOWN)
                : (normal[2] > 0 ? EnumFacing.SOUTH : EnumFacing.NORTH);
        if (PartialFaceLight.applies(blockInfo.getWorld(), blockInfo.getState(), blockInfo.getBlockPos(), surface)) {
            int original = ((int) (lightmap[0] * 65535 / 2) & 0xffff)
                    | (((int) (lightmap[1] * 65535 / 2) & 0xffff) << 16);
            int packed = PartialFaceLight.sample(blockInfo.getWorld(), blockInfo.getState(), blockInfo.getBlockPos(), surface,
                    x - normal[0] * .5f + .5f, y - normal[1] * .5f + .5f, z - normal[2] * .5f + .5f, original);
            lightmap[0] = (packed & 0xffff) * 2f / 65535;
            lightmap[1] = (packed >>> 16) * 2f / 65535;
            return;
        }
        float depth = axis == 0 ? x : axis == 1 ? y : z;
        // Forge shifts surface vertices half a block along their normal. Leave
        // inset and non-axis-aligned custom quads on its original interpolation.
        if (Math.abs(depth) < .9999f) return;
        boolean positive = normal[axis] > 0;
        EnumFacing face = axis == 0 ? (positive ? EnumFacing.EAST : EnumFacing.WEST)
                : axis == 1 ? (positive ? EnumFacing.UP : EnumFacing.DOWN)
                : (positive ? EnumFacing.SOUTH : EnumFacing.NORTH);
        if ((((SmoothLightMatrix) blockInfo).pulsar$hiddenLightFaces() & (1 << face.ordinal())) == 0) return;
        // Forge's rational interpolation also mixes the other faces into this
        // surface. Use only the corrected face when those weights would reintroduce a leak.
        lightmap[0] = pulsar$faceLight(blockInfo.getBlockLight(), axis, positive, x, y, z);
        lightmap[1] = pulsar$faceLight(blockInfo.getSkyLight(), axis, positive, x, y, z);
    }

    @Unique
    private static float pulsar$faceLight(float[][][][] data, int axis, boolean positive, float x, float y, float z) {
        float u = Math.max(0, Math.min(1, (axis == 0 ? y : x) + .5f));
        float v = Math.max(0, Math.min(1, (axis == 2 ? y : z) + .5f));
        int side = positive ? 1 : 0;
        float light = 0;
        for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++) {
            int ix = axis == 0 ? side : a;
            int iy = axis == 1 ? side : axis == 0 ? a : b;
            int iz = axis == 2 ? side : b;
            light += data[axis][ix][iy][iz] * (a == 0 ? 1 - u : u) * (b == 0 ? 1 - v : v);
        }
        return light;
    }
}
