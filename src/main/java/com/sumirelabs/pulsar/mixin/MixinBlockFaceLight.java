package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.api.FaceLightInfo;
import com.sumirelabs.pulsar.api.FaceLitBlock;
import com.sumirelabs.pulsar.api.RawLightAccess;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Facing-aware neighbour-brightness core (MC-92 family).
 *
 * <p>Vanilla's {@code getLightFromNeighborsFor} takes the max light of ALL
 * five up/horizontal neighbours for any {@code useNeighborBrightness}
 * block, smearing the brightest adjacent light across every face of slabs
 * and stairs. Here only the faces a subclass declares "open"
 * ({@link #pulsar$useNeighborBrightness}) contribute: base blocks keep the
 * vanilla-equivalent UP face, slabs/stairs restrict it to their open
 * vertical face (see {@code MixinBlockSlabFaceLight} /
 * {@code MixinBlockStairsFaceLight}).
 */
@Mixin(Block.class)
public abstract class MixinBlockFaceLight implements FaceLitBlock {

    @Override
    public int pulsar$getLightFor(final IBlockState state, final IBlockAccess access,
                                  final EnumSkyBlock lightType, final BlockPos pos) {
        if (!(access instanceof RawLightAccess)) {
            // Unknown IBlockAccess implementation (modded cache we haven't
            // wired): no raw per-type lookup available, nothing sane to
            // compute here. Callers are our own overwrites, which always
            // pass a RawLightAccess — this is a belt for exotic mod calls.
            return lightType.defaultLightValue;
        }
        final RawLightAccess raw = (RawLightAccess) access;
        int lightLevel = raw.pulsar$getRawLight(lightType, pos);
        if (lightLevel == 15 || !state.useNeighborBrightness()) {
            return lightLevel;
        }

        final FaceLightInfo info = (FaceLightInfo) state;
        for (final EnumFacing facing : EnumFacing.VALUES) {
            if (!info.pulsar$useNeighborBrightness(facing, access, pos)) {
                continue;
            }
            int opacity = info.pulsar$getLightOpacity(facing, access, pos);
            final int neighborLight = raw.pulsar$getRawLight(lightType, pos.offset(facing));
            // A fully open face still attenuates by 1 unless it is
            // receiving full sky light (keeps open-to-sky faces at 15).
            if (opacity == 0 && (lightType != EnumSkyBlock.SKY || neighborLight != EnumSkyBlock.SKY.defaultLightValue)) {
                opacity = 1;
            }
            lightLevel = Math.max(lightLevel, neighborLight - opacity);
            if (lightLevel == 15) {
                return lightLevel;
            }
        }
        return lightLevel;
    }

    @Override
    public boolean pulsar$useNeighborBrightness(final IBlockState state, final EnumFacing facing,
                                                final IBlockAccess access, final BlockPos pos) {
        return facing == EnumFacing.UP;
    }

    @Override
    public int pulsar$getLightOpacity(final IBlockState state, final EnumFacing facing,
                                      final IBlockAccess access, final BlockPos pos) {
        return 0;
    }
}
