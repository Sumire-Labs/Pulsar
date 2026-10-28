package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.compat.MultipartLightBridge;
import com.sumirelabs.pulsar.compat.MultipartLightTile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "codechicken.multipart.TileMultipart", remap = false)
public abstract class MixinMultipartLightTile implements MultipartLightTile {
    @Shadow public abstract java.util.List<?> jPartList();
    @Unique private boolean pulsar$policyKnown;
    @Unique private boolean pulsar$pollLight = true;

    // Invalidate only: reading parts here would precede internalPartChange callbacks.
    @Inject(method = {"notifyPartChange", "onChunkLoad", "clearParts", "copyFrom"}, at = @At("HEAD"))
    private void pulsar$invalidateLightPolicy(CallbackInfo ci) { pulsar$policyKnown = false; }

    @Inject(method = "onChunkLoad", at = @At("RETURN"))
    private void pulsar$refreshLoadedLight(CallbackInfo ci) {
        var tile = (net.minecraft.tileentity.TileEntity) (Object) this;
        var world = tile.getWorld();
        if (world == null || world.isRemote || !(world instanceof com.sumirelabs.pulsar.world.PulsarWorld access)) return;
        var manager = access.pulsar$getLightManager();
        if (manager == null) return;
        var pos = tile.getPos();
        manager.queueLightCheck(net.minecraft.world.EnumSkyBlock.BLOCK, pos.getX(), pos.getY(), pos.getZ());
    }

    @Unique
    public boolean pulsar$needsLightPolling() {
        if (!pulsar$policyKnown) {
            pulsar$pollLight = MultipartLightBridge.needsPolling(jPartList());
            pulsar$policyKnown = true;
        }
        return pulsar$pollLight;
    }
}
