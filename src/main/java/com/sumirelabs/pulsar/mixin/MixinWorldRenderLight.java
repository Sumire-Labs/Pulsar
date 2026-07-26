package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.api.FaceLightInfo;
import com.sumirelabs.pulsar.api.RawLightAccess;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Routes the client-side neighbour-brightness lookup on {@link World}
 * through the facing-aware path ({@code MixinBlockFaceLight}). Ported from
 * Alfheim's {@code WorldMixin} (MIT, Red Studio / Desoroxxx).
 */
@Mixin(World.class)
public abstract class MixinWorldRenderLight implements RawLightAccess {

    @Shadow
    public abstract int getLightFor(EnumSkyBlock lightType, BlockPos pos);

    @Shadow
    public abstract IBlockState getBlockState(BlockPos pos);

    /**
     * @reason Facing-aware neighbour brightness (MC-92 family): only the
     * faces a block declares open contribute, instead of vanilla's max over
     * all five up/horizontal neighbours. Bounds/nether guards live in
     * {@code getLightFor}, which the raw path delegates to.
     * @author Luna Mira Lage (Desoroxxx) — Alfheim; ported to Pulsar
     */
    @Overwrite
    @SideOnly(Side.CLIENT)
    public int getLightFromNeighborsFor(final EnumSkyBlock lightType, final BlockPos pos) {
        return ((FaceLightInfo) this.getBlockState(pos)).pulsar$getLightFor((World) (Object) this, lightType, pos);
    }

    @Override
    public int pulsar$getRawLight(final EnumSkyBlock lightType, final BlockPos pos) {
        return this.getLightFor(lightType, pos);
    }
}
