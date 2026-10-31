package com.sumirelabs.pulsar.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.sumirelabs.pulsar.rgb.RgbDynamicVertices;
import com.sumirelabs.pulsar.rgb.RgbFbpLighting;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.util.math.Vec2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Coerce;

/** Fancier 0.7 queues particles and renders them after the ordinary ParticleManager scope. */
@Pseudo
@Mixin(targets = "com.TominoCZ.FBP.renderer.FBPRenderer", remap = false)
public abstract class MixinRgbFbpLegacyRenderer {
    @WrapMethod(method = "putCube_S", remap = false)
    private static void pulsar$tintCube(BufferBuilder buffer, Vec2f[] texture, double scale, @Coerce Object rotation,
                                       int lightU, int lightV, float r, float g, float b, float a, Operation<Void> original) {
        int start = buffer.getVertexCount();
        long generation = ((RgbDynamicVertices) buffer).pulsar$rgbGeneration();
        original.call(buffer, texture, scale, rotation, lightU, lightV, r, g, b, a);
        RgbFbpLighting.apply(buffer, start, generation);
    }

    @WrapMethod(method = "putCube_WH", remap = false)
    private static void pulsar$tintWeather(BufferBuilder buffer, Vec2f[] texture, double width, double height,
                                          @Coerce Object rotation, int lightU, int lightV,
                                          float r, float g, float b, float a, Operation<Void> original) {
        int start = buffer.getVertexCount();
        long generation = ((RgbDynamicVertices) buffer).pulsar$rgbGeneration();
        original.call(buffer, texture, width, height, rotation, lightU, lightV, r, g, b, a);
        RgbFbpLighting.apply(buffer, start, generation);
    }
}
