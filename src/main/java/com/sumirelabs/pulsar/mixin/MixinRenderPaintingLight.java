package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.light.PaintingLightGrid;
import com.sumirelabs.pulsar.light.PaintingLightSampler;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.entity.RenderPainting;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.entity.item.EntityPainting;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds interpolated, per-vertex lightmap coordinates to vanilla paintings. */
@Mixin(RenderPainting.class)
@SideOnly(Side.CLIENT)
public abstract class MixinRenderPaintingLight {

    private static final String RENDER_PAINTING =
            "renderPainting(Lnet/minecraft/entity/item/EntityPainting;IIII)V";

    @Unique
    private PaintingLightGrid pulsar$paintingLight;

    @Unique
    private double pulsar$vertexX;

    @Unique
    private double pulsar$vertexY;

    @Inject(method = RENDER_PAINTING, at = @At("HEAD"))
    private void pulsar$preparePaintingLight(final EntityPainting painting,
                                             final int width, final int height,
                                             final int textureU, final int textureV,
                                             final CallbackInfo callback) {
        this.pulsar$paintingLight = PaintingLightSampler.isSmoothLightingEnabled()
                ? PaintingLightSampler.create(painting)
                : null;
    }

    @ModifyArg(
            method = RENDER_PAINTING,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/BufferBuilder;begin(ILnet/minecraft/client/renderer/vertex/VertexFormat;)V"),
            index = 1)
    private VertexFormat pulsar$usePaintingLightFormat(final VertexFormat original) {
        return this.pulsar$paintingLight == null ? original : PaintingLightSampler.VERTEX_FORMAT;
    }

    @Redirect(
            method = RENDER_PAINTING,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/BufferBuilder;pos(DDD)Lnet/minecraft/client/renderer/BufferBuilder;"))
    private BufferBuilder pulsar$capturePaintingVertex(final BufferBuilder buffer,
                                                       final double x, final double y,
                                                       final double z) {
        if (this.pulsar$paintingLight != null) {
            this.pulsar$vertexX = x;
            this.pulsar$vertexY = y;
        }
        return buffer.pos(x, y, z);
    }

    @Redirect(
            method = RENDER_PAINTING,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/BufferBuilder;normal(FFF)Lnet/minecraft/client/renderer/BufferBuilder;"))
    private BufferBuilder pulsar$writePaintingVertexLight(final BufferBuilder buffer,
                                                          final float x, final float y,
                                                          final float z) {
        if (this.pulsar$paintingLight != null) {
            final int packed = this.pulsar$paintingLight.getLight(
                    this.pulsar$vertexX, this.pulsar$vertexY);
            buffer.lightmap(packed & 0xFFFF, packed >>> 16 & 0xFFFF);
        }
        return buffer.normal(x, y, z);
    }

    @Inject(method = RENDER_PAINTING, at = @At("RETURN"))
    private void pulsar$clearPaintingLight(final EntityPainting painting,
                                           final int width, final int height,
                                           final int textureU, final int textureV,
                                           final CallbackInfo callback) {
        this.pulsar$paintingLight = null;
    }
}
