package com.sumirelabs.pulsar.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.sumirelabs.pulsar.rgb.DynamicObjectLight;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemRenderer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ItemRenderer.class)
public abstract class MixinDynamicHandLight {
    @WrapMethod(method="renderItemInFirstPerson(F)V")
    private void pulsar$handLight(float partial,Operation<Void> original){
        try(var light=DynamicObjectLight.begin(Minecraft.getMinecraft().player,partial)){original.call(partial);}
    }
}
