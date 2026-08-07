package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.api.FaceLightInfo;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

/**
 * Applies Pulsar's facing-aware neighbour brightness to Celeritas's cloned
 * world data without bypassing its asynchronous chunk-mesh pipeline.
 *
 * <p>Celeritas calculates slice-relative coordinates before entering its
 * six-neighbour brightness path. Capturing those coordinates avoids the
 * separate world-to-slice conversion used by Pulsar's former compatibility
 * hook, which could pass invalid section indices to {@code WorldSlice}.
 */
@Pseudo
@Mixin(targets = "org.taumc.celeritas.impl.world.WorldSlice", remap = false)
public abstract class MixinCeleritasWorldSlice {

    /** NOTE: coordinates are relative to Celeritas's cloned section table. */
    @Shadow(remap = false)
    private int getLightFor(final EnumSkyBlock lightType, final int x, final int y, final int z) {
        throw new AssertionError();
    }

    /**
     * Celeritas's first neighbour lookup is the second invocation of
     * {@code getLightFor}; the first invocation belongs to the ordinary
     * non-neighbour-brightness fast path. Intercepting here leaves ordinary
     * blocks untouched and covers both its flat and smooth light caches.
     *
     * <p>The injection is optional so an incompatible future Celeritas
     * version does not prevent Minecraft from starting.
     */
    @Inject(
            method = "getLightFromNeighborsFor(Lnet/minecraft/world/EnumSkyBlock;Lnet/minecraft/util/math/BlockPos;)I",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/taumc/celeritas/impl/world/WorldSlice;getLightFor(Lnet/minecraft/world/EnumSkyBlock;III)I",
                    ordinal = 1),
            cancellable = true,
            locals = LocalCapture.CAPTURE_FAILSOFT,
            require = 0,
            expect = 1,
            remap = false)
    private void pulsar$useFaceAwareNeighborLight(final EnumSkyBlock lightType, final BlockPos pos,
                                                   final CallbackInfoReturnable<Integer> cir,
                                                   final int relX, final int relY, final int relZ,
                                                   final IBlockState state) {
        final IBlockAccess self = (IBlockAccess) (Object) this;
        final FaceLightInfo info = (FaceLightInfo) state;
        int lightLevel = this.getLightFor(lightType, relX, relY, relZ);

        if (lightLevel == 15) {
            cir.setReturnValue(lightLevel);
            return;
        }

        for (final EnumFacing facing : EnumFacing.VALUES) {
            if (!info.pulsar$useNeighborBrightness(facing, self, pos)) {
                continue;
            }

            int opacity = info.pulsar$getLightOpacity(facing, self, pos);
            final int neighborLight = this.getLightFor(lightType,
                    relX + facing.getXOffset(),
                    relY + facing.getYOffset(),
                    relZ + facing.getZOffset());

            if (opacity == 0
                    && (lightType != EnumSkyBlock.SKY || neighborLight != EnumSkyBlock.SKY.defaultLightValue)) {
                opacity = 1;
            }

            lightLevel = Math.max(lightLevel, neighborLight - opacity);
            if (lightLevel == 15) {
                cir.setReturnValue(lightLevel);
                return;
            }
        }

        cir.setReturnValue(lightLevel);
    }
}
