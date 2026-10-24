package com.sumirelabs.pulsar.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.sumirelabs.pulsar.rgb.RgbDrawContext;
import com.sumirelabs.pulsar.rgb.RgbParticleDraw;
import com.sumirelabs.pulsar.rgb.RgbDynamicVertices;
import com.sumirelabs.pulsar.rgb.RgbVertexTint;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ParticleManager.class)
public abstract class MixinRgbParticleManager {
    @WrapMethod(method="renderParticles")
    private void pulsar$particleBatch(Entity camera,float partial,Operation<Void> original) {
        if(!RgbDrawContext.available()){original.call(camera,partial);return;}
        RgbDrawContext.openSession();int token=RgbDrawContext.suppressDraw();
        try { original.call(camera,partial); }
        finally { RgbDrawContext.restoreDraw(token);RgbDrawContext.closeSession(); }
    }
    @WrapMethod(method="renderLitParticles")
    private void pulsar$litBatch(Entity camera,float partial,Operation<Void> original) {
        if(!RgbDrawContext.available()){original.call(camera,partial);return;}
        RgbDrawContext.openSession();
        try { original.call(camera,partial); }
        finally { RgbDrawContext.closeSession(); }
    }
    @WrapOperation(method="renderParticles",at=@At(value="INVOKE",target="Lnet/minecraft/client/particle/Particle;renderParticle(Lnet/minecraft/client/renderer/BufferBuilder;Lnet/minecraft/entity/Entity;FFFFFF)V"))
    private void pulsar$particleDraw(Particle particle,BufferBuilder buffer,Entity camera,float partial,float rx,float rz,float ryz,float rxy,float rxz,Operation<Void> original) {
        int start=buffer.getVertexCount();long generation=((RgbDynamicVertices)buffer).pulsar$rgbGeneration();
        int token=((RgbParticleDraw)particle).pulsar$enterRgbDraw(partial,false);
        try { original.call(particle,buffer,camera,partial,rx,rz,ryz,rxy,rxz);RgbVertexTint.apply(buffer,start,generation,RgbDrawContext.state()); }
        finally { RgbDrawContext.close(token); }
    }
    @WrapOperation(method="renderLitParticles",at=@At(value="INVOKE",target="Lnet/minecraft/client/particle/Particle;renderParticle(Lnet/minecraft/client/renderer/BufferBuilder;Lnet/minecraft/entity/Entity;FFFFFF)V"))
    private void pulsar$litDraw(Particle particle,BufferBuilder buffer,Entity camera,float partial,float rx,float rz,float ryz,float rxy,float rxz,Operation<Void> original) {
        int start=buffer.getVertexCount();long generation=((RgbDynamicVertices)buffer).pulsar$rgbGeneration();
        int token=((RgbParticleDraw)particle).pulsar$enterRgbDraw(partial,true);
        try { original.call(particle,buffer,camera,partial,rx,rz,ryz,rxy,rxz);RgbVertexTint.apply(buffer,start,generation,RgbDrawContext.state()); }
        finally { RgbDrawContext.close(token); }
    }
}
