/*
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.sumirelabs.pulsar.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

import com.sumirelabs.pulsar.api.PositionalColoredLightSource;
import com.sumirelabs.pulsar.compat.IndustrialDynamicLightColors;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;

@Pseudo
@Mixin(targets = "gregtech.common.pipelike.cable.BlockCable", remap = false)
public abstract class MixinRgbGregCable implements PositionalColoredLightSource {

    @Unique
    public int getColoredLightEmission(IBlockAccess world, BlockPos pos, IBlockState state) {
        return IndustrialDynamicLightColors.gregCable(state, world, pos);
    }

}
