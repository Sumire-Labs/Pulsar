package com.sumirelabs.pulsar.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Coerce;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.sumirelabs.pulsar.rgb.RgbDynamicVertices;
import com.sumirelabs.pulsar.rgb.RgbFbpLighting;

import net.minecraft.client.renderer.BufferBuilder;

@Pseudo
@Mixin(targets = {"dev.redstudio.fbp.renderer.FastCubeUploader", "com.fbp.renderer.FastCubeUploader",
    "dev.redstudio.fbp.renderer.FastQuadUploader", "com.fbp.renderer.FastQuadUploader"}, remap = false)
public abstract class MixinRgbFbpUploader {

    @WrapMethod(method = {
        "putCube(Lnet/minecraft/client/renderer/BufferBuilder;Lmeldexun/matrixutil/Matrix4f;Lmeldexun/matrixutil/Matrix3f;Ldev/redstudio/fbp/renderer/texture/ITexCoordProvider;Ldev/redstudio/fbp/renderer/color/IColorProvider;Ldev/redstudio/fbp/renderer/light/ILightCoordProvider;)V",
        "putCube(Lnet/minecraft/client/renderer/BufferBuilder;Lmeldexun/matrixutil/Matrix4f;Lmeldexun/matrixutil/Matrix3f;Lcom/fbp/renderer/texture/ITexCoordProvider;Lcom/fbp/renderer/color/IColorProvider;Lcom/fbp/renderer/light/ILightCoordProvider;)V",
        "putQuad(Lnet/minecraft/client/renderer/BufferBuilder;Lmeldexun/matrixutil/Matrix4f;Lmeldexun/matrixutil/Matrix3f;Ldev/redstudio/fbp/renderer/texture/ITexCoordProvider;Ldev/redstudio/fbp/renderer/color/IColorProvider;Ldev/redstudio/fbp/renderer/light/ILightCoordProvider;)V",
        "putQuad(Lnet/minecraft/client/renderer/BufferBuilder;Lmeldexun/matrixutil/Matrix4f;Lmeldexun/matrixutil/Matrix3f;Lcom/fbp/renderer/texture/ITexCoordProvider;Lcom/fbp/renderer/color/IColorProvider;Lcom/fbp/renderer/light/ILightCoordProvider;)V"
    }, remap = false)
    private static void pulsar$tintGeometry(BufferBuilder buffer, @Coerce Object model, @Coerce Object normal,
        @Coerce Object texture, @Coerce Object color, @Coerce Object light,
        Operation<Void> original) {
        int start = buffer.getVertexCount();
        long generation = ((RgbDynamicVertices) buffer).pulsar$rgbGeneration();
        original.call(buffer, model, normal, texture, color, light);
        RgbFbpLighting.apply(buffer, start, generation);
    }

}
