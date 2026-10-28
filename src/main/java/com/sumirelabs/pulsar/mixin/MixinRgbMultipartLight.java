package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.api.PositionalColoredLightSource;
import com.sumirelabs.pulsar.compat.MultipartLightBridge;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

@Pseudo
@Mixin(targets = "codechicken.multipart.BlockMultipart", remap = false)
public abstract class MixinRgbMultipartLight implements PositionalColoredLightSource {
    @Unique
    public int getColoredLightEmission(IBlockAccess world, BlockPos pos, IBlockState state) {
        return MultipartLightBridge.emission(state, world, pos);
    }
}
