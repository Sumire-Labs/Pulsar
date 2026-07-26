package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.api.FaceLightInfo;
import com.sumirelabs.pulsar.api.FaceLitBlock;
import com.sumirelabs.pulsar.light.LightCachedState;
import com.sumirelabs.pulsar.light.engine.LightInfo;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Attaches the memoised packed light info to every block state. 1.12.2
 * analogue of Starlight's {@code BlockStateBaseMixin} per-state light cache
 * (1.14+ {@code BlockBehaviour.BlockStateBase}).
 *
 * <p>Forge's {@code ExtendedBlockState$ExtendedStateImplementation} extends
 * this class, so extended states inherit the field.
 *
 * <p>The lazy init is racy on purpose: {@link LightInfo#compute} is pure and
 * deterministic, an {@code int} write is atomic, and both BFS workers plus
 * the client thread computing the same value concurrently simply store the
 * same result. First use happens in-world, safely after
 * {@code FaceOcclusion.registerDefaults()} has run at postInit.
 */
@Mixin(targets = "net.minecraft.block.state.BlockStateContainer$StateImplementation")
public abstract class MixinBlockStateImplementation implements LightCachedState, FaceLightInfo {

    @Unique
    private int pulsar$lightInfo;

    @Override
    public int pulsar$lightInfo() {
        final int info = this.pulsar$lightInfo;
        if (info != 0) {
            return info;
        }
        return this.pulsar$lightInfo = LightInfo.compute((IBlockState) this);
    }

    // ---- FaceLightInfo: delegate to the block's FaceLitBlock methods ----
    // (MixinBlockFaceLight attaches FaceLitBlock to every Block on both
    // sides, so the casts below cannot fail.)

    @Override
    public int pulsar$getLightFor(final IBlockAccess access, final EnumSkyBlock lightType, final BlockPos pos) {
        final IBlockState self = (IBlockState) this;
        return ((FaceLitBlock) self.getBlock()).pulsar$getLightFor(self, access, lightType, pos);
    }

    @Override
    public boolean pulsar$useNeighborBrightness(final EnumFacing facing, final IBlockAccess access, final BlockPos pos) {
        final IBlockState self = (IBlockState) this;
        return ((FaceLitBlock) self.getBlock()).pulsar$useNeighborBrightness(self, facing, access, pos);
    }

    @Override
    public int pulsar$getLightOpacity(final EnumFacing facing, final IBlockAccess access, final BlockPos pos) {
        final IBlockState self = (IBlockState) this;
        return ((FaceLitBlock) self.getBlock()).pulsar$getLightOpacity(self, facing, access, pos);
    }
}
