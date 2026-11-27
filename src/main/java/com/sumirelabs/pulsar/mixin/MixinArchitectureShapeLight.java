/* SPDX-License-Identifier: LGPL-3.0-only */
package com.sumirelabs.pulsar.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.sumirelabs.pulsar.compat.ArchitectureShapeLight;

import net.minecraft.tileentity.TileEntity;

@Pseudo
@Mixin(targets = "com.elytradev.architecture.common.tile.TileShape", remap = false)
public abstract class MixinArchitectureShapeLight implements ArchitectureShapeLight {

    @Unique private volatile boolean pulsar$opacityNotifications;
    @Unique private volatile boolean pulsar$watchingOpacity;

    @Inject(method = {"invalidateLightOpacity()V", "setConnectionEnabledGlobal(Lnet/minecraft/util/EnumFacing;Z)V",
        "readShapeFromNBT(Lnet/minecraft/nbt/NBTTagCompound;)V"}, at = @At("RETURN"), require = 0)
    private void pulsar$shapeOpacityChanged(CallbackInfo ci) {
        pulsar$opacityNotifications = true;
        // Chunk load and tile installation already schedule the first capture.
        if (pulsar$watchingOpacity)
            ArchitectureShapeLight.invalidated((TileEntity) (Object) this);
    }

    public boolean pulsar$subscribeToOpacityChanges() {
        pulsar$watchingOpacity = true;
        return pulsar$opacityNotifications;
    }

}
