package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.light.RenderLightRules;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BlockModelRenderer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Restores smooth lighting for blocks whose emission is only level one.
 */
@Mixin(BlockModelRenderer.class)
@SideOnly(Side.CLIENT)
public abstract class MixinBlockModelRendererLight {

    private static final String MCP_RENDER_MODEL =
            "renderModel(Lnet/minecraft/world/IBlockAccess;"
                    + "Lnet/minecraft/client/renderer/block/model/IBakedModel;"
                    + "Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/util/math/BlockPos;"
                    + "Lnet/minecraft/client/renderer/BufferBuilder;ZJ)Z";
    private static final String VANILLA_LIGHT_VALUE =
            "Lnet/minecraft/block/state/IBlockState;getLightValue("
                    + "Lnet/minecraft/world/IBlockAccess;Lnet/minecraft/util/math/BlockPos;)I";
    private static final String OPTIFINE_LIGHT_VALUE =
            "Lnet/optifine/reflect/ReflectorForge;getLightValue("
                    + "Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/world/IBlockAccess;"
                    + "Lnet/minecraft/util/math/BlockPos;)I";

    @Redirect(
            method = MCP_RENDER_MODEL,
            at = @At(
                    value = "INVOKE",
                    target = VANILLA_LIGHT_VALUE,
                    remap = false),
            require = 0,
            expect = 0)
    private int pulsar$adjustAmbientOcclusionLightValue(
            final IBlockState state, final IBlockAccess access, final BlockPos pos) {
        return RenderLightRules.ambientOcclusionEmission(state.getLightValue(access, pos));
    }

    /**
     * Optional OptiFine call site for the same renderer method.
     */
    @Redirect(
            method = MCP_RENDER_MODEL,
            at = @At(
                    value = "INVOKE",
                    target = OPTIFINE_LIGHT_VALUE,
                    remap = false),
            require = 0,
            expect = 0)
    private int pulsar$adjustOptiFineAmbientOcclusionLightValue(
            final IBlockState state, final IBlockAccess access, final BlockPos pos) {
        return RenderLightRules.ambientOcclusionEmission(state.getLightValue(access, pos));
    }

}
