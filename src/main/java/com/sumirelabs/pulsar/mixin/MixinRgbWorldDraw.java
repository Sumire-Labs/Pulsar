package com.sumirelabs.pulsar.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.sumirelabs.pulsar.rgb.RgbDrawContext;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(RenderGlobal.class)
public abstract class MixinRgbWorldDraw {
    @WrapMethod(method="renderEntities")
    private void pulsar$worldDraw(Entity camera, ICamera frustum, float partial, Operation<Void> original) {
        if (!RgbDrawContext.available()) { original.call(camera,frustum,partial); return; }
        var state = RgbDrawContext.state(); int token = state.enterWorld(); RgbDrawContext.openSession();
        try { original.call(camera,frustum,partial); }
        finally { state.leaveWorld(token); RgbDrawContext.closeSession(); }
    }
}
