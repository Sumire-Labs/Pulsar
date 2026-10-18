package com.sumirelabs.pulsar.light.nativeprobe.mixin;

import com.sumirelabs.pulsar.light.PartialFaceLight;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Restores d71a04b's own-partial-block-only selection for the regression control JVM. */
@Mixin(value = PartialFaceLight.class, remap = false)
public abstract class LegacyStairWallSelection {
    @Inject(method = "applies(Lnet/minecraft/world/IBlockAccess;Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/EnumFacing;)Z",
            at = @At("HEAD"), cancellable = true)
    private static void previousSelection(IBlockAccess world, IBlockState state, BlockPos pos, EnumFacing normal,
                                           CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(PartialFaceLight.applies(state));
    }
}
