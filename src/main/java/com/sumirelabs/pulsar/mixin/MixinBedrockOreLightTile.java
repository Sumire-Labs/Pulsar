package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.compat.BedrockOreLightTile;
import com.sumirelabs.pulsar.compat.BedrockOreLighting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "li.cil.bedrockores.common.tileentity.TileEntityBedrockOre", remap = false)
public abstract class MixinBedrockOreLightTile implements BedrockOreLightTile {
    @Unique private boolean pulsar$oreNotifications;

    // In 1.2.7.42 both disk NBT and client update NBT delegate to this setter.
    // In an unfamiliar version without this method, retain fallback polling.
    @Inject(method = "setOreBlockState", at = @At("RETURN"), require = 0)
    private void pulsar$oreLightChanged(CallbackInfo ci) {
        pulsar$oreNotifications = true;
        // Notify also on bright -> dark/null. The original mod checks lighting
        // only when the new ore emits light, leaving that transition unnotified.
        BedrockOreLighting.changed((net.minecraft.tileentity.TileEntity)(Object)this);
    }

    public boolean pulsar$hasOreLightNotifications() { return pulsar$oreNotifications; }
}
