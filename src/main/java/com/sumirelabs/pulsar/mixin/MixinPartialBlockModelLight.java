package com.sumirelabs.pulsar.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.sumirelabs.pulsar.light.PartialFaceLight;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BlockModelRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(BlockModelRenderer.class)
public abstract class MixinPartialBlockModelLight {
    @ModifyArgs(method = "renderQuadsSmooth", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/BufferBuilder;putBrightness4(IIII)V"))
    private void pulsar$lightActualSurface(Args args, @Local(argsOnly = true) IBlockAccess world,
                                           @Local(argsOnly = true) IBlockState state,
                                           @Local(argsOnly = true) BlockPos pos, @Local BakedQuad quad) {
        if (!PartialFaceLight.applies(world, state, pos, quad.getFace())) return;
        int[] vertices = quad.getVertexData();
        int stride = quad.getFormat().getIntegerSize();
        for (int vertex = 0; vertex < 4; vertex++) {
            int index = vertex * stride;
            args.set(vertex, PartialFaceLight.sample(world, state, pos, quad.getFace(),
                    Float.intBitsToFloat(vertices[index]), Float.intBitsToFloat(vertices[index + 1]),
                    Float.intBitsToFloat(vertices[index + 2]), args.get(vertex)));
        }
    }
}
