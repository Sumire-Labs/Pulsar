package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.rgb.RgbDrawContext;
import com.sumirelabs.pulsar.rgb.RgbParticleDraw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(Particle.class)
public abstract class MixinRgbParticle implements RgbParticleDraw {
    @Shadow protected World world;
    @Shadow protected double prevPosX,prevPosY,prevPosZ,posX,posY,posZ;
    public int pulsar$enterRgbDraw(float partial,boolean uniform) {
        if (world != Minecraft.getMinecraft().world) return 0;
        double x=prevPosX+(posX-prevPosX)*partial,y=prevPosY+(posY-prevPosY)*partial,z=prevPosZ+(posZ-prevPosZ)*partial;
        return uniform?RgbDrawContext.enter(this,x,y,z):RgbDrawContext.enterVertices(this,x,y,z);
    }
}
