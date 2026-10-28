package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.light.LightUpdateTracer;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(World.class)
public abstract class MixinLightUpdateTrace {
    @Inject(method="markBlockRangeForRenderUpdate(IIIIII)V",at=@At("HEAD"))
    private void pulsar$traceRender(int x1,int y1,int z1,int x2,int y2,int z2,CallbackInfo ci) {
        if(!LightUpdateTracer.enabled())return;
        World world=(World)(Object)this;
        if(world.isRemote)LightUpdateTracer.record(LightUpdateTracer.Kind.RENDER_REQUEST,world.provider.getDimension(),x1,y1,z1);
    }
}
