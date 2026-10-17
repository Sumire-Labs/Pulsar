package com.sumirelabs.pulsar.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.sumirelabs.pulsar.light.SmoothLightMatrix;
import com.sumirelabs.pulsar.light.SmoothLightOcclusion;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.client.model.pipeline.BlockInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = BlockInfo.class, remap = false)
public abstract class MixinForgeLightMatrix implements SmoothLightMatrix {
    @Shadow private IBlockAccess world;
    @Shadow private BlockPos blockPos;
    @Unique private int pulsar$sampleIndex, pulsar$hiddenFaces;
    @Unique private final BlockPos.MutableBlockPos pulsar$corner = new BlockPos.MutableBlockPos();

    public int pulsar$hiddenLightFaces() { return pulsar$hiddenFaces; }

    @Inject(method = "updateLightMatrix", at = @At("HEAD"))
    private void pulsar$beginMatrix(CallbackInfo ci) {
        pulsar$sampleIndex = pulsar$hiddenFaces = 0;
    }

    @WrapOperation(method = "updateLightMatrix", at = @At(value = "INVOKE", target =
            "Lnet/minecraftforge/client/model/pipeline/BlockInfo;combine(IIIIZZZZ)F"))
    private float pulsar$excludeHiddenLight(@Coerce Object self, int center, int a, int b, int diagonal,
                                            boolean ct, boolean at, boolean bt, boolean dt,
                                            Operation<Float> original) {
        int index = pulsar$sampleIndex++;
        if (a <= center && b <= center && diagonal <= center) return original.call(self, center, a, b, diagonal, ct, at, bt, dt);
        int vertex = index / 6;
        int x = vertex >> 2, y = vertex >> 1 & 1, z = vertex & 1;
        int axis = index % 6 / 2;
        EnumFacing normal = axis == 0 ? (x == 0 ? EnumFacing.WEST : EnumFacing.EAST)
                : axis == 1 ? (y == 0 ? EnumFacing.DOWN : EnumFacing.UP)
                : (z == 0 ? EnumFacing.NORTH : EnumFacing.SOUTH);
        int corner = SmoothLightOcclusion.cornerIndex(normal, x, y, z);
        int mask = SmoothLightOcclusion.hiddenSamples(world, blockPos.getX() + normal.getXOffset(),
                blockPos.getY() + normal.getYOffset(), blockPos.getZ() + normal.getZOffset(), normal, corner, 0, pulsar$corner);
        // Forge's Y and north matrices supply the two tangent edges in reverse order.
        if (axis == 1 || normal == EnumFacing.NORTH) mask = (mask & 4) | ((mask & 1) << 1) | ((mask & 2) >> 1);
        boolean hideA = (mask & 1) != 0 && a > center;
        boolean hideB = (mask & 2) != 0 && b > center;
        boolean hideDiagonal = (mask & 4) != 0 && diagonal > center;
        if (hideA || hideB || hideDiagonal) pulsar$hiddenFaces |= 1 << normal.ordinal();
        return original.call(self, center, hideA ? center : a, hideB ? center : b, hideDiagonal ? center : diagonal,
                ct, hideA ? ct : at, hideB ? ct : bt, hideDiagonal ? ct : dt);
    }
}
