package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.compat.ThaumcraftCrystalLighting;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Supplies real block light through the same Forge emission method used by other light sources. */
@Pseudo
@Mixin(targets = "thaumcraft.common.blocks.world.ore.BlockCrystal", remap = false)
public abstract class MixinThaumcraftCrystalLight {

    // Forge-added method: the name and descriptor are identical in MCP and SRG.
    @Inject(method = "getLightValue(Lnet/minecraft/block/state/IBlockState;"
            + "Lnet/minecraft/world/IBlockAccess;Lnet/minecraft/util/math/BlockPos;)I",
            at = @At("RETURN"), cancellable = true, remap = false, require = 1)
    private void pulsar$crystalEmission(final IBlockState state, final IBlockAccess world,
                                       final BlockPos pos, final CallbackInfoReturnable<Integer> cir) {
        final int original = cir.getReturnValueI();
        final int emission = ThaumcraftCrystalLighting.emission(original);
        if (emission != original) {
            cir.setReturnValue(emission);
        }
    }
}
