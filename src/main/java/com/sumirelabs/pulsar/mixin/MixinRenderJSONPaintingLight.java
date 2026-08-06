package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.light.PaintingLightGrid;
import com.sumirelabs.pulsar.light.PaintingLightSampler;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.entity.item.EntityPainting;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds interpolated lightmap coordinates to JSON Paintings' special renderer. */
@Pseudo
@Mixin(targets = "git.jbredwards.jsonpaintings.mod.client.RenderJSONPainting", remap = false)
@SideOnly(Side.CLIENT)
public abstract class MixinRenderJSONPaintingLight {

    private static final String RENDER_PAINTING =
            "renderPainting(Lnet/minecraft/entity/item/EntityPainting;"
                    + "Lgit/jbredwards/jsonpaintings/mod/common/util/IJSONPainting;)V";

    private static final String BUFFER_BEGIN_MCP =
            "Lnet/minecraft/client/renderer/BufferBuilder;"
                    + "begin(ILnet/minecraft/client/renderer/vertex/VertexFormat;)V";

    private static final String BUFFER_BEGIN_SRG =
            "Lnet/minecraft/client/renderer/BufferBuilder;"
                    + "func_181668_a(ILnet/minecraft/client/renderer/vertex/VertexFormat;)V";

    private static final String BUFFER_POS_MCP =
            "Lnet/minecraft/client/renderer/BufferBuilder;"
                    + "pos(DDD)Lnet/minecraft/client/renderer/BufferBuilder;";

    private static final String BUFFER_POS_SRG =
            "Lnet/minecraft/client/renderer/BufferBuilder;"
                    + "func_181662_b(DDD)Lnet/minecraft/client/renderer/BufferBuilder;";

    private static final String BUFFER_NORMAL_MCP =
            "Lnet/minecraft/client/renderer/BufferBuilder;"
                    + "normal(FFF)Lnet/minecraft/client/renderer/BufferBuilder;";

    private static final String BUFFER_NORMAL_SRG =
            "Lnet/minecraft/client/renderer/BufferBuilder;"
                    + "func_181663_c(FFF)Lnet/minecraft/client/renderer/BufferBuilder;";

    @Shadow(remap = false)
    public static boolean CALC_BRIGHTNESS;

    @Unique
    private PaintingLightGrid pulsar$jsonPaintingLight;

    @Unique
    private double pulsar$jsonVertexX;

    @Unique
    private double pulsar$jsonVertexY;

    @Inject(method = RENDER_PAINTING, at = @At("HEAD"), remap = false, require = 0)
    private void pulsar$prepareJsonPaintingLight(final EntityPainting painting,
                                                 @Coerce final Object definition,
                                                 final CallbackInfo callback) {
        this.pulsar$jsonPaintingLight = CALC_BRIGHTNESS
                && PaintingLightSampler.isSmoothLightingEnabled()
                ? PaintingLightSampler.create(painting)
                : null;
    }

    @ModifyArg(
            method = RENDER_PAINTING,
            at = @At(value = "INVOKE", target = BUFFER_BEGIN_MCP, remap = false),
            index = 1,
            remap = false,
            require = 0)
    @Group(name = "pulsar$jsonPaintingBegin", min = 1)
    private VertexFormat pulsar$useJsonPaintingLightFormatMcp(final VertexFormat original) {
        return this.pulsar$useJsonPaintingLightFormat(original);
    }

    @ModifyArg(
            method = RENDER_PAINTING,
            at = @At(value = "INVOKE", target = BUFFER_BEGIN_SRG, remap = false),
            index = 1,
            remap = false,
            require = 0)
    @Group(name = "pulsar$jsonPaintingBegin", min = 1)
    private VertexFormat pulsar$useJsonPaintingLightFormatSrg(final VertexFormat original) {
        return this.pulsar$useJsonPaintingLightFormat(original);
    }

    @Redirect(
            method = RENDER_PAINTING,
            at = @At(value = "INVOKE", target = BUFFER_POS_MCP, remap = false),
            remap = false,
            require = 0)
    @Group(name = "pulsar$jsonPaintingPosition", min = 1)
    private BufferBuilder pulsar$captureJsonPaintingVertexMcp(final BufferBuilder buffer,
                                                              final double x, final double y,
                                                              final double z) {
        return this.pulsar$captureJsonPaintingVertex(buffer, x, y, z);
    }

    @Redirect(
            method = RENDER_PAINTING,
            at = @At(value = "INVOKE", target = BUFFER_POS_SRG, remap = false),
            remap = false,
            require = 0)
    @Group(name = "pulsar$jsonPaintingPosition", min = 1)
    private BufferBuilder pulsar$captureJsonPaintingVertexSrg(final BufferBuilder buffer,
                                                              final double x, final double y,
                                                              final double z) {
        return this.pulsar$captureJsonPaintingVertex(buffer, x, y, z);
    }

    @Redirect(
            method = RENDER_PAINTING,
            at = @At(value = "INVOKE", target = BUFFER_NORMAL_MCP, remap = false),
            remap = false,
            require = 0)
    @Group(name = "pulsar$jsonPaintingNormal", min = 1)
    private BufferBuilder pulsar$writeJsonPaintingVertexLightMcp(final BufferBuilder buffer,
                                                                 final float x, final float y,
                                                                 final float z) {
        return this.pulsar$writeJsonPaintingVertexLight(buffer, x, y, z);
    }

    @Redirect(
            method = RENDER_PAINTING,
            at = @At(value = "INVOKE", target = BUFFER_NORMAL_SRG, remap = false),
            remap = false,
            require = 0)
    @Group(name = "pulsar$jsonPaintingNormal", min = 1)
    private BufferBuilder pulsar$writeJsonPaintingVertexLightSrg(final BufferBuilder buffer,
                                                                 final float x, final float y,
                                                                 final float z) {
        return this.pulsar$writeJsonPaintingVertexLight(buffer, x, y, z);
    }

    @Inject(method = RENDER_PAINTING, at = @At("RETURN"), remap = false, require = 0)
    private void pulsar$clearJsonPaintingLight(final EntityPainting painting,
                                               @Coerce final Object definition,
                                               final CallbackInfo callback) {
        this.pulsar$jsonPaintingLight = null;
    }

    @Unique
    private VertexFormat pulsar$useJsonPaintingLightFormat(final VertexFormat original) {
        return this.pulsar$jsonPaintingLight == null ? original : PaintingLightSampler.VERTEX_FORMAT;
    }

    @Unique
    private BufferBuilder pulsar$captureJsonPaintingVertex(final BufferBuilder buffer,
                                                           final double x, final double y,
                                                           final double z) {
        if (this.pulsar$jsonPaintingLight != null) {
            this.pulsar$jsonVertexX = x;
            this.pulsar$jsonVertexY = y;
        }
        return buffer.pos(x, y, z);
    }

    @Unique
    private BufferBuilder pulsar$writeJsonPaintingVertexLight(final BufferBuilder buffer,
                                                              final float x, final float y,
                                                              final float z) {
        if (this.pulsar$jsonPaintingLight != null) {
            final int packed = this.pulsar$jsonPaintingLight.getLight(
                    this.pulsar$jsonVertexX, this.pulsar$jsonVertexY);
            buffer.lightmap(packed & 0xFFFF, packed >>> 16 & 0xFFFF);
        }
        return buffer.normal(x, y, z);
    }
}
