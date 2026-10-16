package com.sumirelabs.pulsar.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.sumirelabs.pulsar.rgb.RgbDrawContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemRenderer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ItemRenderer.class)
public abstract class MixinRgbHandRenderer {
    @WrapMethod(method="renderItemInFirstPerson(F)V")
    private void pulsar$handDraw(float partial,Operation<Void> original) {
        var player = Minecraft.getMinecraft().player;
        int token = player == null ? 0 : RgbDrawContext.enter(player,
                player.lastTickPosX+(player.posX-player.lastTickPosX)*partial,
                player.lastTickPosY+(player.posY-player.lastTickPosY)*partial+player.getEyeHeight(),
                player.lastTickPosZ+(player.posZ-player.lastTickPosZ)*partial);
        try { original.call(partial); }
        finally { RgbDrawContext.close(token); }
    }
}
