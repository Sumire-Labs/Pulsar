package com.sumirelabs.pulsar.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.sumirelabs.pulsar.light.SmoothLightOcclusion;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.BitSet;

/** Retain vanilla's AO weights and interpolation while filtering hidden light per corner. */
@Mixin(targets = "net.minecraft.client.renderer.BlockModelRenderer$AmbientOcclusionFace")
public abstract class MixinSmoothLightOcclusion {
    @Unique private final BlockPos.MutableBlockPos pulsar$corner = new BlockPos.MutableBlockPos();
    @Unique private int pulsar$cornerIndex;

    @Inject(method = "updateVertexBrightness", at = @At("HEAD"))
    private void pulsar$beginFace(IBlockAccess world, IBlockState state, BlockPos pos, EnumFacing face,
                                  float[] bounds, BitSet flags, CallbackInfo ci) {
        pulsar$cornerIndex = 0;
    }

    @WrapOperation(method = "updateVertexBrightness", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/renderer/BlockModelRenderer$AmbientOcclusionFace;getAoBrightness(IIII)I"))
    private int pulsar$excludeHiddenLight(@Coerce Object ao, int a, int b, int diagonal, int centerLight,
                                         Operation<Integer> original,
                                         @Local(argsOnly = true) IBlockAccess world,
                                         @Local(argsOnly = true) BlockPos center,
                                         @Local(argsOnly = true) EnumFacing normal,
                                         @Local(argsOnly = true) BitSet flags) {
        int corner = pulsar$cornerIndex++;
        // These corner coordinates describe a block boundary. Keep vanilla's
        // existing weighting for inset/non-cubic planes inside the block.
        if (!flags.get(0)) return original.call(ao, a, b, diagonal, centerLight);
        // Uniform light, including fully dark faces, needs no additional world reads.
        if (a == centerLight && b == centerLight && diagonal == centerLight) return original.call(ao, a, b, diagonal, centerLight);
        int depth = flags.get(0) ? 1 : 0;
        int mask = SmoothLightOcclusion.hiddenSamples(world,
                center.getX() + depth * normal.getXOffset(), center.getY() + depth * normal.getYOffset(),
                center.getZ() + depth * normal.getZOffset(), normal, corner, 0, pulsar$corner);
        return original.call(ao, (mask & 1) != 0 ? SmoothLightOcclusion.limitLight(a, centerLight) : a,
                (mask & 2) != 0 ? SmoothLightOcclusion.limitLight(b, centerLight) : b,
                (mask & 4) != 0 ? SmoothLightOcclusion.limitLight(diagonal, centerLight) : diagonal, centerLight);
    }
}
