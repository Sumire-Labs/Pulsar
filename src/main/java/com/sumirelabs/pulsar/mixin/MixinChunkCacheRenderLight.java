package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.api.FaceLightInfo;
import com.sumirelabs.pulsar.api.RawLightAccess;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.ChunkCache;
import net.minecraft.world.EnumSkyBlock;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Same facing-aware routing as {@code MixinWorldRenderLight}, for the
 * vanilla render-thread {@link ChunkCache}.
 */
@Mixin(ChunkCache.class)
public abstract class MixinChunkCacheRenderLight implements RawLightAccess {

    @Shadow
    public abstract int getLightFor(EnumSkyBlock lightType, BlockPos pos);

    @Shadow
    public abstract IBlockState getBlockState(BlockPos pos);

    /**
     * @reason Facing-aware neighbour brightness (MC-92 family) for the
     * render-thread chunk cache.
     * @author Sumire Labs
     */
    @Overwrite
    @SideOnly(Side.CLIENT)
    private int getLightForExt(final EnumSkyBlock lightType, final BlockPos pos) {
        return ((FaceLightInfo) this.getBlockState(pos)).pulsar$getLightFor((ChunkCache) (Object) this, lightType, pos);
    }

    @Override
    public int pulsar$getRawLight(final EnumSkyBlock lightType, final BlockPos pos) {
        return this.getLightFor(lightType, pos);
    }
}
