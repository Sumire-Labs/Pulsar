package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.light.RenderLightRules;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Client render-side halves of the non-full-block lighting fix.
 */
@Mixin(Block.class)
public abstract class MixinBlockRenderLight {

    /**
     * @reason Drop the vanilla "slab samples the block below when dark"
     * hack — the facing-aware neighbour-brightness path (MixinBlockFaceLight
     * + the World/ChunkCache/WorldSlice overwrites) computes the correct
     * value at this position instead. (MC-92 family)
     * @author Sumire Labs
     */
    @Overwrite
    @SideOnly(Side.CLIENT)
    public int getPackedLightmapCoords(final IBlockState state, final IBlockAccess source, final BlockPos pos) {
        return source.getCombinedLight(pos, state.getLightValue(source, pos));
    }

    /**
     * @reason Strong light-emitting blocks must not create ambient-occlusion
     * shadows on themselves (MC-249343). Level-one emitters retain smooth
     * lighting through MixinBlockModelRendererLight (MC-50734).
     * @author Sumire Labs
     */
    @Overwrite
    @SideOnly(Side.CLIENT)
    public float getAmbientOcclusionLightValue(final IBlockState state) {
        final int emission = RenderLightRules.ambientOcclusionEmission(state.getLightValue());
        if (emission == 0) {
            return state.isBlockNormalCube() ? 0.2F : 1.0F;
        }
        return 1.0F;
    }
}
