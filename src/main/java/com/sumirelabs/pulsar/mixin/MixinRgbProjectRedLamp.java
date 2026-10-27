package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.api.PositionalColoredLightSource;
import com.sumirelabs.pulsar.compat.ProjectRedLampColors;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** No ProjectRed dependency or world access from a lighting worker. Excluded in SCALAR. */
@Pseudo
@Mixin(targets="mrtjp.projectred.illumination.BlockLamp",remap=false)
public abstract class MixinRgbProjectRedLamp implements PositionalColoredLightSource {
    @org.spongepowered.asm.mixin.Unique
    public int getColoredLightEmission(IBlockAccess world,BlockPos pos,IBlockState state) {
        return ProjectRedLampColors.emission(state,world,pos);
    }
}
