package com.sumirelabs.pulsar.light;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.entity.item.EntityPainting;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/** Client-only sampling and vertex-format support for painting light grids. */
@SideOnly(Side.CLIENT)
public final class PaintingLightSampler {

    /** Position, texture UV, lightmap UV, and normal for painting geometry. */
    public static final VertexFormat VERTEX_FORMAT = new VertexFormat()
            .addElement(DefaultVertexFormats.POSITION_3F)
            .addElement(DefaultVertexFormats.TEX_2F)
            .addElement(DefaultVertexFormats.TEX_2S)
            .addElement(DefaultVertexFormats.NORMAL_3B)
            .addElement(DefaultVertexFormats.PADDING_1B);

    private PaintingLightSampler() {
    }

    /**
     * Builds a grid using the same tile-centre positions as vanilla's
     * {@code RenderPainting#setLightmap} method.
     */
    public static PaintingLightGrid create(final EntityPainting painting) {
        final int width = painting.art.sizeX >> 4;
        final int height = painting.art.sizeY >> 4;
        if (width <= 0 || height <= 0 || painting.world == null) {
            return null;
        }

        final int[] tileLight = new int[width * height];
        final float minX = -painting.art.sizeX / 2.0F;
        final float minY = -painting.art.sizeY / 2.0F;
        for (int tileY = 0; tileY < height; tileY++) {
            for (int tileX = 0; tileX < width; tileX++) {
                final float centerX = minX + (tileX + 0.5F) * 16.0F;
                final float centerY = minY + (tileY + 0.5F) * 16.0F;
                final BlockPos samplePos = samplePosition(
                        painting.posX, painting.posY, painting.posZ,
                        painting.facingDirection, centerX, centerY);
                tileLight[tileX + tileY * width] = painting.world.getCombinedLight(samplePos, 0);
            }
        }

        return PaintingLightGrid.fromTileLight(width, height, tileLight);
    }

    /** Returns whether the player has requested smooth lighting. */
    public static boolean isSmoothLightingEnabled() {
        return Minecraft.getMinecraft().gameSettings.ambientOcclusion != 0;
    }

    private static BlockPos samplePosition(final double paintingX, final double paintingY,
                                           final double paintingZ, final EnumFacing facing,
                                           final float localX, final float localY) {
        int x = MathHelper.floor(paintingX);
        final int y = MathHelper.floor(paintingY + localY / 16.0F);
        int z = MathHelper.floor(paintingZ);

        switch (facing) {
            case NORTH:
                x = MathHelper.floor(paintingX + localX / 16.0F);
                break;
            case WEST:
                z = MathHelper.floor(paintingZ - localX / 16.0F);
                break;
            case SOUTH:
                x = MathHelper.floor(paintingX - localX / 16.0F);
                break;
            case EAST:
                z = MathHelper.floor(paintingZ + localX / 16.0F);
                break;
            default:
                break;
        }

        return new BlockPos(x, y, z);
    }
}
