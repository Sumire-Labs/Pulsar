package com.sumirelabs.pulsar.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.sumirelabs.pulsar.rgb.RgbDrawContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(RenderManager.class)
public abstract class MixinRgbEntityRenderer {
    @WrapMethod(method="renderEntity")
    private void pulsar$entityDraw(Entity entity,double x,double y,double z,float yaw,float partial,boolean debug,Operation<Void> original) {
        int token = 0;
        if (RgbDrawContext.state().worldPass() && entity.world == Minecraft.getMinecraft().world)
            token = RgbDrawContext.enter(entity,
                    entity.lastTickPosX+(entity.posX-entity.lastTickPosX)*partial,
                    entity.lastTickPosY+(entity.posY-entity.lastTickPosY)*partial+entity.getEyeHeight(),
                    entity.lastTickPosZ+(entity.posZ-entity.lastTickPosZ)*partial);
        try { original.call(entity,x,y,z,yaw,partial,debug); }
        finally { RgbDrawContext.close(token); }
    }
    @WrapMethod(method="renderDebugBoundingBox")
    private void pulsar$debugDraw(Entity entity,double x,double y,double z,float yaw,float partial,Operation<Void> original) {
        int token = RgbDrawContext.enterUnlit();
        try { original.call(entity,x,y,z,yaw,partial); }
        finally { RgbDrawContext.close(token); }
    }
}
