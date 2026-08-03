package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.api.FaceLightInfo;
import com.sumirelabs.pulsar.api.RawLightAccess;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Celeritas clones chunk data into its own {@code WorldSlice} for the
 * chunk-build workers and replicates the vanilla neighbour-brightness max
 * there — bypassing the {@code World}/{@code ChunkCache} fixes. Route it
 * through the same facing-aware path so terrain meshes get the MC-92 fix
 * too. {@code @Pseudo}: the target only exists when Celeritas is installed.
 */
@Pseudo
@Mixin(targets = "org.taumc.celeritas.impl.world.WorldSlice", remap = false)
public abstract class MixinCeleritasWorldSlice implements RawLightAccess {

    /**
     * NOTE: takes coordinates RELATIVE to {@code baseX/baseY/baseZ}.
     */
    @Shadow(remap = false)
    private int getLightFor(final EnumSkyBlock lightType, final int x, final int y, final int z) {
        throw new AssertionError();
    }

    @Shadow(remap = false)
    private boolean hasSkyLight() {
        throw new AssertionError();
    }

    @Shadow(remap = false)
    @Final
    private int defaultSkyLightValue;

    @Shadow(remap = false)
    private int baseX;

    @Shadow(remap = false)
    private int baseY;

    @Shadow(remap = false)
    private int baseZ;

    /**
     * @reason Facing-aware neighbour brightness (MC-92 family) for
     * Celeritas's cloned world slice, matching the World/ChunkCache
     * behaviour. Volume bounds are already checked by the caller
     * (getCombinedLight); the raw lookup handles the rest.
     * @author Sumire Labs
     */
    @Overwrite(remap = false)
    private int getLightFromNeighborsFor(final EnumSkyBlock lightType, final BlockPos pos) {
        final IBlockAccess self = (IBlockAccess) this;
        return ((FaceLightInfo) self.getBlockState(pos)).pulsar$getLightFor(self, lightType, pos);
    }

    @Override
    public int pulsar$getRawLight(final EnumSkyBlock lightType, final BlockPos pos) {
        // Mirrors the original getLightFromNeighborsFor's preamble: sky in a
        // skyless dimension short-circuits, and the raw accessor takes
        // coordinates RELATIVE to the slice origin (world coords index far
        // outside the 4x4x4 section table — AIOOBE).
        if (lightType == EnumSkyBlock.SKY && !this.hasSkyLight()) {
            return this.defaultSkyLightValue;
        }
        return this.getLightFor(lightType,
                pos.getX() - this.baseX, pos.getY() - this.baseY, pos.getZ() - this.baseZ);
    }
}
