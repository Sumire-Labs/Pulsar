package com.sumirelabs.pulsar.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.sumirelabs.pulsar.light.CeleritasLightCache;
import com.sumirelabs.pulsar.light.SmoothLightOcclusion;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "org.embeddedt.embeddium.impl.model.light.smooth.AoFaceData", remap = false)
public abstract class MixinCeleritasSmoothLight {
    @Unique private IBlockAccess pulsar$world;
    @Unique private EnumFacing pulsar$normal;
    @Unique private int pulsar$x, pulsar$y, pulsar$z, pulsar$cornerIndex;
    @Unique private final BlockPos.MutableBlockPos pulsar$corner = new BlockPos.MutableBlockPos();

    @Inject(method = "initLightData", at = @At("HEAD"), remap = false)
    private void pulsar$beginFace(@Coerce Object cache, int x, int y, int z, @Coerce Object direction,
                                  boolean offset, CallbackInfo ci) {
        // The unoffset plane is used for inset/non-axis-aligned model interpolation.
        pulsar$world = offset && cache instanceof CeleritasLightCache bridge ? bridge.pulsar$lightWorld() : null;
        pulsar$cornerIndex = 0;
        if (pulsar$world == null) return;
        pulsar$normal = SmoothLightOcclusion.celeritasFacing(direction);
        pulsar$x = x + (offset ? pulsar$normal.getXOffset() : 0);
        pulsar$y = y + (offset ? pulsar$normal.getYOffset() : 0);
        pulsar$z = z + (offset ? pulsar$normal.getZOffset() : 0);
    }

    @WrapOperation(method = "initLightData", at = @At(value = "INVOKE", target =
            "Lorg/embeddedt/embeddium/impl/model/light/smooth/AoFaceData;calculateCornerBrightness(IIIIZZZZ)I"), remap = false)
    private int pulsar$excludeHiddenLight(int a, int b, int diagonal, int center,
                                         boolean aem, boolean bem, boolean cem, boolean dem,
                                         Operation<Integer> original) {
        int corner = pulsar$cornerIndex++;
        if (pulsar$world == null || (a == center && b == center && diagonal == center && aem == dem && bem == dem && cem == dem)) {
            return original.call(a, b, diagonal, center, aem, bem, cem, dem);
        }
        int mask = SmoothLightOcclusion.hiddenSamples(pulsar$world, pulsar$x, pulsar$y, pulsar$z,
                pulsar$normal, corner, (aem ? 1 : 0) | (bem ? 2 : 0) | (cem ? 4 : 0), pulsar$corner);
        return original.call((mask & 1) != 0 ? SmoothLightOcclusion.limitLight(a, center) : a,
                (mask & 2) != 0 ? SmoothLightOcclusion.limitLight(b, center) : b,
                (mask & 4) != 0 ? SmoothLightOcclusion.limitLight(diagonal, center) : diagonal, center,
                (mask & 1) != 0 ? dem : aem, (mask & 2) != 0 ? dem : bem, (mask & 4) != 0 ? dem : cem, dem);
    }
}
