package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.compat.CqrBulkLighting;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "team.cqr.cqrepoured.world.structure.generation.generation.GeneratableDungeon", remap = false)
public abstract class MixinCqrBulkLighting {
    @Inject(method = "generate(Lnet/minecraft/world/World;)V", at = @At("RETURN"), require = 0, remap = false)
    private void pulsar$refreshBulkLight(final World world, final CallbackInfo ci) {
        CqrBulkLighting.relight(world, this);
    }
}
