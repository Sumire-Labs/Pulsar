package com.sumirelabs.pulsar.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.sumirelabs.pulsar.dynamic.DynamicLights;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;

/** Gameplay overlay at query boundaries only; propagation and persistence keep their raw data. */
@Mixin(World.class)
public abstract class MixinDynamicWorldLight {

    @Inject(method = "getLightFor", at = @At("RETURN"), cancellable = true)
    private void pulsar$dynamicBlockLight(EnumSkyBlock type, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (type == EnumSkyBlock.BLOCK)
            cir.setReturnValue(Math.max(cir.getReturnValueI(),
                DynamicLights.gameplay((World) (Object) this, pos.getX(), pos.getY(), pos.getZ())));
    }
    @Inject(method = "getLight(Lnet/minecraft/util/math/BlockPos;Z)I", at = @At("RETURN"), cancellable = true)
    private void pulsar$dynamicCombinedLight(BlockPos pos, boolean checkNeighbors,
        CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(Math.max(cir.getReturnValueI(),
            DynamicLights.gameplay((World) (Object) this, pos.getX(), pos.getY(), pos.getZ())));
    }
    @Inject(method = "getLightFromNeighbors", at = @At("RETURN"), cancellable = true)
    private void pulsar$dynamicNeighborLight(BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(Math.max(cir.getReturnValueI(),
            DynamicLights.gameplay((World) (Object) this, pos.getX(), pos.getY(), pos.getZ())));
    }

}
